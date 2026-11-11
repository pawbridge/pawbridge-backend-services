"""에이전트 모드의 요청 처리.

    공고와 질문으로 근거 찾기(루프 밖, 매번) → 루프(graph.py) → 답 검사 → 기록 → 응답

고정 흐름과 응답 모양이 같아 백엔드와 화면은 모드를 모른다. 실패는 종류에 따라 나눈다.
- 상류 장애(429, 5xx, 연결 실패): 고정 흐름도 같은 제공자를 부르므로 다시 시도하지 않고 고정 흐름처럼 502.
- 시간 초과, 토큰 예산 초과: 더 부르지 않고 정해진 대체 답변.
- 루프의 논리 실패(모델 호출 상한, 빈 답, 잘림, 단계 상한): 남은 시간이 있으면 고정 흐름, 없으면 대체 답변.
"""

import logging
import time

from fastapi import HTTPException, status

from app.chatbot.agent.graph import (
    DEADLINE_SECONDS,
    STOP_DEADLINE,
    STOP_TOKEN_BUDGET,
    AgentContext,
    AgentLedger,
    AgentRun,
    run_agent,
)
from app.chatbot.agent.models import chat_model, forced_tool_call_settings, model_call_settings, reasoning_level
from app.chatbot.agent.similar import SIMILAR_FALLBACK, unknown_notice_numbers
from app.chatbot.agent.tools import ToolContext
from app.chatbot.health.answer import (
    FALLBACK_ANSWER,
    NO_EVIDENCE_FALLBACK,
    CheckedAnswer,
    answer_text_from_json,
    check_answer,
    check_plain_answer,
    health_cards,
    sources_for,
    strip_citations,
    unknown_phone_numbers,
)
from app.chatbot.prompt_builder import build_agent_prompt
from app.chatbot.provider import (
    ChatbotProviderConfigurationError,
    ChatbotProviderTimeoutError,
    ChatbotProviderTruncatedError,
    ChatbotProviderUpstreamError,
)
from app.chatbot.service import (
    answer_fixed,
    chatbot_response,
    find_health_evidence,
    health_retriever,
    log_health_answer,
    provider_or_500,
)

logger = logging.getLogger("uvicorn.error")

# 고정 흐름은 모델을 한 번 부른다. 남은 시간이 이보다 적으면 부르지 않고 대체 답변으로 끝낸다.
FIXED_FLOW_MIN_SECONDS = 5.0
STOP_TRUNCATED = "truncated"
# 고정 흐름으로 넘기지 않는 멈춤. 시간이 없거나, 이미 예산을 넘겨 더 부르면 안 된다.
NO_FIXED_FLOW_STOPS = {STOP_DEADLINE, STOP_TOKEN_BUDGET}

PATH_AGENT = "agent"
PATH_FIXED = "fixed"
PATH_FALLBACK = "fallback"
PATH_ERROR = "error"


async def create_agent_message(req) -> dict:
    started = time.monotonic()
    deadline = started + DEADLINE_SECONDS
    animal = req.animalContext
    matches, evidence = find_health_evidence(animal, req.question)
    provider = provider_or_500()
    try:
        model = chat_model()
    except ChatbotProviderConfigurationError as exc:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(exc)) from exc
    prompt = build_agent_prompt(animal, req.recentMessages, req.question, evidence, req.shelterContext)
    tools = ToolContext(
        health_retriever(),
        animal.specialMark,
        animal.species,
        matches=list(matches),
        evidence=list(evidence),
        animal_id=req.animalId,
    )
    context = AgentContext(
        model,
        tools,
        deadline,
        model_settings=model_call_settings(),
        forced_tool_settings=forced_tool_call_settings(),
    )
    ledger = context.ledger

    run = None
    try:
        run = await run_agent(prompt, context)
    except (ChatbotProviderTimeoutError, TimeoutError):
        ledger.stop = STOP_DEADLINE
    except ChatbotProviderTruncatedError:
        ledger.stop = STOP_TRUNCATED
    except ChatbotProviderUpstreamError as exc:
        log_agent_run(PATH_ERROR, "upstream", ledger, started, error=exc)
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc)) from exc
    stop = ledger.stop

    if stop is None:
        response = finish_agent_answer(provider.name, run, animal.species, req.shelterContext, tools.similar_notice_numbers)
        log_agent_run(PATH_AGENT, None, ledger, started)
        return response

    remaining = deadline - time.monotonic()
    if stop not in NO_FIXED_FLOW_STOPS and remaining >= FIXED_FLOW_MIN_SECONDS:
        try:
            response = await answer_fixed(provider, req, matches, evidence, timeout=remaining)
        except HTTPException as exc:
            if not isinstance(exc.__cause__, ChatbotProviderTimeoutError):
                log_agent_run(PATH_ERROR, stop, ledger, started, error=exc.__cause__)
                raise
        else:
            log_agent_run(PATH_FIXED, stop, ledger, started)
            return response

    # 대체 답변. 루프가 더 찾은 근거는 검사를 거치지 않았으므로 처음 찾은 해설만 카드로 보낸다.
    log_health_answer(matches, [], True)
    log_agent_run(PATH_FALLBACK, stop, ledger, started)
    cards = health_cards(matches, animal.species) if evidence else []
    return chatbot_response(provider.name, FALLBACK_ANSWER if evidence else NO_EVIDENCE_FALLBACK, [], cards)


def finish_agent_answer(
    provider_name: str,
    run: AgentRun,
    species: str | None,
    shelter,
    similar_notice_numbers: set[str] | frozenset[str] = frozenset(),
) -> dict:
    """루프가 낸 답을 고정 흐름과 같은 검사에 통과시킨다. 도구로 더 찾은 근거도 검사 범위에 들어간다."""
    evidence = run.evidence
    if evidence:
        checked = check_answer(run.answer, evidence, expect_json=True)
    else:
        text = answer_text_from_json(run.answer)
        if text is None:
            checked = CheckedAnswer(NO_EVIDENCE_FALLBACK, [], ["JSON 응답을 해석할 수 없음"], fallback=True)
        else:
            checked = check_plain_answer(text)

    # 보호소 정보를 프롬프트에 넣으므로 모델이 번호를 잘못 옮기거나 지어낸 답을 막는다.
    if not checked.fallback and unknown_phone_numbers(checked.answer, [shelter.shelterPhone if shelter else None]):
        checked = CheckedAnswer(
            FALLBACK_ANSWER if evidence else NO_EVIDENCE_FALLBACK,
            [],
            ["보호소 정보에 없는 전화번호"],
            fallback=True,
        )
    # 비슷한 동물 도구가 돌려준 동물만 소개해야 한다. 결과에 없는 공고번호는 지어낸 동물로 본다.
    if not checked.fallback and unknown_notice_numbers(checked.answer, similar_notice_numbers):
        checked = CheckedAnswer(
            SIMILAR_FALLBACK,
            [],
            ["비슷한 동물 결과에 없는 공고번호"],
            fallback=True,
        )

    log_health_answer(run.matches, checked.used, checked.fallback)
    sources = sources_for(checked.used, evidence) if evidence else []
    cards = health_cards(run.matches, species) if evidence else []
    return chatbot_response(provider_name, strip_citations(checked.answer), sources, cards)


def log_agent_run(path: str, stop: str | None, ledger: AgentLedger, started: float, error=None) -> None:
    """에이전트가 어떻게 끝났는지 숫자로 남긴다. 질문, 검색어, 답 내용은 넣지 않는다.

    토큰은 루프 안 모델 호출의 합이다(고정 흐름으로 넘어간 호출은 Gemini 응답 로그에 따로 남는다).
    error는 상류 장애와 우리 코드 문제를 로그에서 나누려고 오류 종류 이름만 남긴다.
    """
    logger.info(
        "Chatbot agent: path=%s stop=%s error=%s model_calls=%d tool_calls=%d tool_errors=%d "
        "input_tokens=%d output_tokens=%d reasoning_tokens=%d reasoning=%s elapsed_ms=%d",
        path,
        stop or "-",
        _error_name(error),
        ledger.model_calls,
        ledger.tool_calls,
        ledger.tool_errors,
        ledger.input_tokens,
        ledger.output_tokens,
        ledger.reasoning_tokens,
        reasoning_level(),
        (time.monotonic() - started) * 1000,
    )


def _error_name(error) -> str:
    """ChatbotProviderUpstreamError는 원인(제공자 오류) 종류를 남긴다. 예: GoogleRateLimitError."""
    if error is None:
        return "-"
    cause = error.__cause__ if isinstance(error, ChatbotProviderUpstreamError) and error.__cause__ else error
    return type(cause).__name__
