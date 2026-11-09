"""입양 상담 에이전트의 루프(LangGraph).

    시작 → model ─(도구 호출)→ tools → model … ─(답 또는 멈춤)→ 끝

model 노드는 제공자에게 한 턴을 받고, tools 노드는 tools.py의 함수를 실행한다.
상한(도구 호출, 모델 호출, 전체 시간)은 모두 코드가 센다. 모델이 무엇을 부르든 이 울타리를 넘지 못한다.
답 검사와 대체 답변은 그래프 밖(agent/service.py)에서 한다. 그래프는 원문 답이나 멈춘 이유만 돌려준다.
"""

import asyncio
import time
from dataclasses import dataclass
from functools import lru_cache
from typing import Annotated, TypedDict

from langchain_core.messages import AIMessage, AnyMessage, HumanMessage, ToolMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.runtime import Runtime
from pydantic import ValidationError

from app.chatbot.agent.tools import TOOLS, ToolContext
from app.chatbot.health.answer import Evidence
from app.chatbot.health.retriever import HealthMatch, HealthRetriever
from app.chatbot.provider import ChatbotProvider

MAX_TOOL_CALLS = 3
MAX_MODEL_CALLS = 4
DEADLINE_SECONDS = 20.0
# 남은 시간이 이보다 적으면 모델을 다시 부르지 않는다(부르더라도 답을 받기 전에 끊긴다).
MIN_CALL_SECONDS = 1.5

# 멈춘 이유. 로그에 그대로 남기므로 질문 내용 없이 짧은 코드로 둔다.
STOP_MODEL_LIMIT = "model_limit"
STOP_DEADLINE = "deadline"
STOP_UNKNOWN_TOOL = "unknown_tool"
STOP_BAD_ARGS = "bad_tool_args"
STOP_EMPTY = "empty_answer"


class AgentState(TypedDict):
    messages: Annotated[list[AnyMessage], add_messages]
    matches: list[HealthMatch]
    evidence: list[Evidence]
    model_calls: int
    tool_calls: int
    stop: str | None


@dataclass(frozen=True)
class AgentContext:
    """요청마다 정해지고 루프 동안 바뀌지 않는 값."""

    provider: ChatbotProvider
    retriever: HealthRetriever
    notice_text: str | None
    species: str | None
    # time.monotonic() 기준 마감 시각
    deadline: float


@dataclass
class AgentRun:
    answer: str | None
    matches: list[HealthMatch]
    evidence: list[Evidence]
    model_calls: int
    tool_calls: int
    stop: str | None


async def call_model(state: AgentState, runtime: Runtime[AgentContext]) -> dict:
    context = runtime.context
    if state["model_calls"] >= MAX_MODEL_CALLS:
        return {"stop": STOP_MODEL_LIMIT}
    remaining = context.deadline - time.monotonic()
    if remaining < MIN_CALL_SECONDS:
        return {"stop": STOP_DEADLINE}
    # 도구 상한에 닿았거나 마지막 모델 호출이면 도구 호출을 막아 이번에 답을 내게 한다.
    allow_tools = state["tool_calls"] < MAX_TOOL_CALLS and state["model_calls"] < MAX_MODEL_CALLS - 1
    tools = [spec for spec, _ in TOOLS.values()]
    reply = await context.provider.generate_turn(state["messages"], tools, allow_tools, remaining)
    return {"messages": [reply], "model_calls": state["model_calls"] + 1}


def run_tools(state: AgentState, runtime: Runtime[AgentContext]) -> dict:
    context = runtime.context
    tool_context = ToolContext(
        context.retriever,
        context.notice_text,
        context.species,
        matches=list(state["matches"]),
        evidence=list(state["evidence"]),
    )
    used = state["tool_calls"]
    results = []
    for call in state["messages"][-1].tool_calls:
        if call["name"] not in TOOLS:
            return {"stop": STOP_UNKNOWN_TOOL}
        spec, function = TOOLS[call["name"]]
        if used >= MAX_TOOL_CALLS:
            # 한 턴에 여러 개를 불러 상한을 넘긴 몫. 도구 결과 자리는 채워야 다음 호출이 받아들여진다.
            content = "도구 호출 상한에 닿았습니다. 지금까지 받은 근거로 답하세요."
        else:
            try:
                args = spec.args_schema.model_validate(call["args"])
            except ValidationError:
                return {"stop": STOP_BAD_ARGS}
            content = function(args, tool_context)
            used += 1
        results.append(ToolMessage(content=content, tool_call_id=call["id"], name=call["name"]))
    return {
        "messages": results,
        "matches": tool_context.matches,
        "evidence": tool_context.evidence,
        "tool_calls": used,
    }


def after_model(state: AgentState) -> str:
    if state.get("stop"):
        return END
    last = state["messages"][-1]
    if isinstance(last, AIMessage) and last.tool_calls:
        return "tools"
    return END


def after_tools(state: AgentState) -> str:
    return END if state.get("stop") else "model"


@lru_cache(maxsize=1)
def agent_graph():
    """그래프는 한 번만 만든다. 요청마다 다른 값은 AgentContext로 넘긴다. 대화 저장(checkpointer)은 쓰지 않는다."""
    graph = StateGraph(AgentState, context_schema=AgentContext)
    graph.add_node("model", call_model)
    graph.add_node("tools", run_tools)
    graph.add_edge(START, "model")
    graph.add_conditional_edges("model", after_model, ["tools", END])
    graph.add_conditional_edges("tools", after_tools, ["model", END])
    return graph.compile()


async def run_agent(
    prompt: str,
    matches: list[HealthMatch],
    evidence: list[Evidence],
    context: AgentContext,
) -> AgentRun:
    """루프를 돌려 원문 답(검사 전)이나 멈춘 이유를 돌려준다.

    제공자 오류(상류 장애, 시간 초과, 잘림)는 그대로 올려 보낸다. 부른 쪽이 종류에 따라 처리한다.
    """
    initial: AgentState = {
        "messages": [HumanMessage(content=prompt)],
        "matches": list(matches),
        "evidence": list(evidence),
        "model_calls": 0,
        "tool_calls": 0,
        "stop": None,
    }
    remaining = max(0.0, context.deadline - time.monotonic())
    # 노드마다 남은 시간을 보지만, 제공자가 시간 제한을 지키지 않아도 마감에서 끊는다.
    state = await asyncio.wait_for(
        agent_graph().ainvoke(initial, context=context, config={"recursion_limit": 2 * MAX_MODEL_CALLS + 2}),
        timeout=remaining,
    )
    stop = state.get("stop")
    answer = None
    last = state["messages"][-1]
    if not stop and isinstance(last, AIMessage) and not last.tool_calls:
        answer = last.content if isinstance(last.content, str) else None
        if not answer or not answer.strip():
            answer, stop = None, STOP_EMPTY
    return AgentRun(answer, state["matches"], state["evidence"], state["model_calls"], state["tool_calls"], stop)
