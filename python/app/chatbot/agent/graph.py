"""입양 상담 에이전트의 루프. LangChain create_agent(LangGraph 위)에 울타리 미들웨어 하나를 단다.

    시작 → [울타리: 멈출까?] → model ─(도구 호출)→ tools → [울타리] → model … ─(답 또는 멈춤)→ 끝

- model: 요청마다 AgentContext.model(agent/models.py가 설정으로 만든 채팅 모델)을 부른다.
- tools: tools.py의 평범한 함수를 LangChain 도구로 감싸 실행한다. 한 턴에 부른 여러 호출은 동시에 돈다.
- 울타리(AgentLimits): 모델 호출 수, 도구 호출 수, 전체 시간, 토큰 예산을 코드가 센다.
  모델이 무엇을 부르든 이 울타리를 넘지 못한다.
- 도구 이름이나 입력이 틀리면 LangChain이 오류 문구를 모델에 돌려주고, 모델이 고쳐 다시 부른다.
  틀린 호출은 도구 횟수에 세지 않지만 모델 호출 횟수에는 들어간다.
답 검사와 대체 답변은 그래프 밖(agent/service.py)에서 한다. 그래프는 원문 답이나 멈춘 이유만 돌려준다.
"""

import asyncio
import inspect
import time
from dataclasses import dataclass, field
from functools import lru_cache

from langchain.agents import create_agent
from langchain.agents.middleware import AgentMiddleware, hook_config
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_core.tools import StructuredTool
from langgraph.errors import GraphRecursionError
from langgraph.runtime import get_runtime
from langsmith.run_helpers import tracing_context

from app.chatbot.agent.models import classify_model_error
from app.chatbot.agent.tools import TOOLS, ToolContext, ToolSpec
from app.chatbot.health.answer import Evidence
from app.chatbot.health.retriever import HealthMatch
from app.chatbot.provider import ChatbotProviderTruncatedError

MAX_TOOL_CALLS = 3
# 도구 3회 + 실수 1회 + 답 1회. 고정 흐름으로 넘어가면 질문 하나에 모델을 최대 6번 부른다.
MAX_MODEL_CALLS = 5
DEADLINE_SECONDS = 20.0
# 남은 시간이 이보다 적으면 모델을 다시 부르지 않는다(부르더라도 답을 받기 전에 끊긴다).
MIN_CALL_SECONDS = 1.5
# 질문 하나에서 모든 모델 호출의 입력과 출력(생각 포함) 토큰 합. 넘으면 다음 호출을 하지 않는다.
# 호출 도중에는 못 끊으므로 마지막 호출 하나만큼은 넘을 수 있다.
# 정상 최대의 약 2배. 11/10 실제 Gemini에서 한글이 약 0.4~0.5토큰/자라, 대화가 길고 검색을 3번 다 쓰는
# 정상 질문을 약 1만 2천으로 잡았다(실측: 도구 1회 질문 약 3,300). 모든 칸을 끝까지 채운 구조상 최대는 약 3만 8천.
TOKEN_BUDGET = 24_000
# 반복 한 번이 3단계(울타리, model, tools)다. 상한에 닿아 멈추는 경우(16단계)보다 조금 넉넉하게 둔다.
RECURSION_LIMIT = 3 * MAX_MODEL_CALLS + 4

TOOL_LIMIT_MESSAGE = "도구 호출 상한에 닿았습니다. 지금까지 받은 근거로 답하세요."
# 답이 잘렸다는 종료 사유. 제공자마다 이름과 칸이 다르다(Gemini는 finish_reason MAX_TOKENS,
# Anthropic은 stop_reason max_tokens).
TRUNCATED_FINISH_REASONS = {"MAX_TOKENS", "length", "max_tokens"}

# 멈춘 이유. 로그에 그대로 남기므로 질문 내용 없이 짧은 코드로 둔다.
STOP_MODEL_LIMIT = "model_limit"
STOP_DEADLINE = "deadline"
STOP_TOKEN_BUDGET = "token_budget"
STOP_EMPTY = "empty_answer"
STOP_LOOP_LIMIT = "loop_limit"


@dataclass
class AgentLedger:
    """요청 하나 동안 울타리가 세는 값. 한 턴의 도구 호출이 동시에 돌므로 상태(state)가 아니라 여기에 센다."""

    model_calls: int = 0
    tool_calls: int = 0
    tool_errors: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    reasoning_tokens: int = 0
    stop: str | None = None

    @property
    def total_tokens(self) -> int:
        return self.input_tokens + self.output_tokens

    def add_usage(self, usage: dict | None) -> None:
        """LangChain 공통 usage_metadata를 더한다. output_tokens에는 생각 토큰이 들어 있다."""
        if not usage:
            return
        self.input_tokens += usage.get("input_tokens") or 0
        self.output_tokens += usage.get("output_tokens") or 0
        self.reasoning_tokens += (usage.get("output_token_details") or {}).get("reasoning") or 0


@dataclass(frozen=True)
class AgentContext:
    """요청마다 정해지는 값. 그래프는 한 번만 만들고 이 값만 요청마다 바꿔 넘긴다."""

    model: BaseChatModel
    tools: ToolContext
    # time.monotonic() 기준 마감 시각
    deadline: float
    ledger: AgentLedger = field(default_factory=AgentLedger)
    # 모델 호출마다 붙일 제공자별 설정(예: Gemini 일시 오류 재시도). models.model_call_settings()가 만든다.
    model_settings: dict = field(default_factory=dict)
    # 도구 호출을 강제하는 호출에만 더 붙일 제공자별 설정. models.forced_tool_call_settings()가 만든다.
    forced_tool_settings: dict = field(default_factory=dict)


@dataclass
class AgentRun:
    answer: str | None
    matches: list[HealthMatch]
    evidence: list[Evidence]
    ledger: AgentLedger

    @property
    def stop(self) -> str | None:
        return self.ledger.stop


class AgentLimits(AgentMiddleware):
    """에이전트의 모든 상한과 모델 오류 분류를 한 곳에서 맡는다. 비동기 실행(ainvoke)만 지원한다."""

    @hook_config(can_jump_to=["end"])
    def before_model(self, state, runtime):
        context = runtime.context
        ledger = context.ledger
        # 예산과 시간은 고정 흐름도 건너뛰는 이유라 먼저 본다.
        if ledger.total_tokens >= TOKEN_BUDGET:
            ledger.stop = STOP_TOKEN_BUDGET
        elif context.deadline - time.monotonic() < MIN_CALL_SECONDS:
            ledger.stop = STOP_DEADLINE
        elif ledger.model_calls >= MAX_MODEL_CALLS:
            ledger.stop = STOP_MODEL_LIMIT
        return {"jump_to": "end"} if ledger.stop else None

    async def awrap_model_call(self, request, handler):
        context = request.runtime.context
        ledger = context.ledger
        ledger.model_calls += 1
        # 도구 상한에 닿았거나 마지막 호출이면 도구 호출만 막아 이번에 답을 내게 한다.
        # 선언은 남긴다(대화에 이미 도구 호출이 있으면 선언이 있어야 제공자가 받아 준다).
        blocked = ledger.tool_calls >= MAX_TOOL_CALLS or ledger.model_calls >= MAX_MODEL_CALLS
        tool_choice = "none" if blocked else None
        # 코드의 첫 검색이 근거를 하나도 못 찾았으면 첫 호출에서 도구 하나를 반드시 부르게 한다.
        # 모델은 부를지는 고르지 못하고 어느 도구와 입력을 쓸지만 고른다(건강이면 search_health,
        # 입양 절차면 adoption_guide). 11/11 시범 실행에서 모델에게 맡기면 첫 검색이 놓친 건강 질문
        # 18개 중 minimal 0개, low와 강화한 규칙으로도 12개만 불렀다.
        settings = {**request.model_settings, **context.model_settings}
        # 비슷한 동물 질문은 공고 특이사항으로 건강 근거를 이미 찾았어도 그 도구를 강제한다.
        # 11/11 운영 확인(공고 106382, 특이사항에 종양, 피부병)에서 근거가 있어 강제가 걸리지 않았고,
        # 모델은 도구 없이 "그런 기능은 없다"고 답했다.
        if not blocked and ledger.model_calls == 1 and context.tools.first_tool:
            tool_choice = context.tools.first_tool
            settings = {**settings, **context.forced_tool_settings}
        elif not blocked and ledger.model_calls == 1 and not context.tools.evidence:
            tool_choice = "any"
            settings = {**settings, **context.forced_tool_settings}
        request = request.override(model=context.model, tool_choice=tool_choice, model_settings=settings)
        remaining = max(0.0, context.deadline - time.monotonic())
        try:
            # 제공자의 시간 제한과 별개로 남은 시간에서 끊는다(제공자가 시간 제한을 지키지 않아도).
            response = await asyncio.wait_for(handler(request), timeout=remaining)
        except Exception as exc:
            classified = classify_model_error(exc)
            if classified is None:
                raise
            raise classified from exc

        message = _last_ai_message(response.result)
        if message is not None:
            ledger.add_usage(message.usage_metadata)
            metadata = message.response_metadata
            if (metadata.get("finish_reason") or metadata.get("stop_reason")) in TRUNCATED_FINISH_REASONS:
                raise ChatbotProviderTruncatedError("Model response was truncated by token limit")
        return response

    async def awrap_tool_call(self, request, handler):
        ledger = request.runtime.context.ledger
        call = request.tool_call
        known = request.tool is not None
        if known and ledger.tool_calls >= MAX_TOOL_CALLS:
            # 한 턴에 여러 개를 불러 상한을 넘긴 몫. 결과 자리는 채워야 다음 모델 호출이 받아들여진다.
            return ToolMessage(content=TOOL_LIMIT_MESSAGE, tool_call_id=call["id"], name=call["name"])
        if known:
            # 실행 전에 자리를 잡는다. 동시에 도는 호출들이 함께 상한을 넘지 않게 한다.
            ledger.tool_calls += 1
        result = await handler(request)
        if isinstance(result, ToolMessage) and result.status == "error":
            # 없는 도구나 틀린 입력. LangChain이 만든 오류 문구가 모델에 그대로 간다.
            ledger.tool_errors += 1
            if known:
                ledger.tool_calls -= 1
        return result


def _last_ai_message(messages) -> AIMessage | None:
    for message in reversed(messages or []):
        if isinstance(message, AIMessage):
            return message
    return None


def final_answer_text(message: AIMessage) -> str:
    """모델의 마지막 메시지에서 답 글만 꺼낸다.

    Gemini 3 이상은 content가 조각 목록일 수 있어 text로 이어 붙인다.
    OpenAI Responses API는 최종 답 앞에 중간 설명 메시지를 붙일 수 있고, 조각마다 phase를 단다
    (final_answer, commentary). phase가 final_answer인 조각이 있으면 그것만 쓴다. 이어 붙이면 JSON이 깨진다.
    """
    if isinstance(message.content, list):
        finals = [
            block.get("text", "")
            for block in message.content
            if isinstance(block, dict) and block.get("type") == "text" and block.get("phase") == "final_answer"
        ]
        if finals:
            return "".join(finals)
    return str(message.text)


def _langchain_tool(spec: ToolSpec, function) -> StructuredTool:
    """tools.py의 평범한 함수를 LangChain 도구로 감싼다. 입력 검사는 LangChain이 spec.args_schema로 한다.

    건강 해설 검색은 기다림(await)이 없어 동시에 불려도 한 번에 하나씩 끝까지 돈다. 그래서 근거 번호가 겹치지 않는다.
    비슷한 동물 도구는 백엔드 API를 기다리는 async 함수다. 근거를 건드리지 않으므로 겹쳐 돌아도 된다.
    """

    async def run(**kwargs) -> str:
        context = get_runtime(AgentContext).context
        result = function(spec.args_schema(**kwargs), context.tools)
        if inspect.isawaitable(result):
            result = await result
        return result

    return StructuredTool.from_function(
        coroutine=run, name=spec.name, description=spec.description, args_schema=spec.args_schema
    )


class _ModelPerRequest(BaseChatModel):
    """그래프를 만들 때 자리만 채운다. 실제 모델은 요청마다 AgentLimits가 AgentContext.model로 바꿔 끼운다.

    그래서 그래프는 한 번만 만들고, 모델 설정이 바뀌거나 시험이 가짜 모델을 넣어도 다시 만들지 않는다.
    """

    @property
    def _llm_type(self) -> str:
        return "per-request"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        raise RuntimeError("AgentLimits must replace the placeholder model for each request")


@lru_cache(maxsize=1)
def agent_graph():
    """그래프는 한 번만 만든다. 대화 저장(checkpointer)은 쓰지 않는다(대화 원본은 백엔드 DB)."""
    tools = [_langchain_tool(spec, function) for spec, function in TOOLS.values()]
    return create_agent(_ModelPerRequest(), tools=tools, middleware=[AgentLimits()], context_schema=AgentContext)


async def run_agent(prompt: str, context: AgentContext) -> AgentRun:
    """루프를 돌려 원문 답(검사 전)이나 멈춘 이유를 돌려준다.

    제공자 오류(상류 장애, 시간 초과, 잘림)는 그대로 올려 보낸다. 부른 쪽이 종류에 따라 처리한다.
    도는 동안 센 값(context.ledger)은 오류가 나도 남는다.
    """
    ledger = context.ledger
    remaining = max(0.0, context.deadline - time.monotonic())
    state = None
    # LangSmith 추적을 끈다. 환경변수(LANGSMITH_TRACING)가 켜져 있어도 질문과 공고가 밖으로 나가지 않게 한다.
    with tracing_context(enabled=False):
        try:
            # 노드마다 남은 시간을 보지만, 도구 실행까지 포함해 마감에서 한 번 더 끊는다.
            state = await asyncio.wait_for(
                agent_graph().ainvoke(
                    {"messages": [HumanMessage(content=prompt)]},
                    context=context,
                    config={"recursion_limit": RECURSION_LIMIT},
                ),
                timeout=remaining,
            )
        except GraphRecursionError:
            # 울타리가 있어 닿지 않아야 한다. 닿으면 논리 실패로 보고 고정 흐름에 넘긴다.
            ledger.stop = STOP_LOOP_LIMIT

    answer = None
    if state is not None and not ledger.stop:
        last = state["messages"][-1]
        if isinstance(last, AIMessage) and not last.tool_calls:
            answer = final_answer_text(last).strip() or None
        if answer is None:
            ledger.stop = STOP_EMPTY
    tools = context.tools
    return AgentRun(answer, tools.matches, tools.evidence, ledger)
