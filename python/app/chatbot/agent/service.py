"""에이전트 모드의 요청 처리.

    공고와 질문으로 근거 찾기(루프 밖, 매번) → [차단기] → 루프(graph.py) → [못 쓰는 제공자면 예비 제공자로 루프 다시] → 답 검사 → 기록 → 응답

고정 흐름과 응답 모양이 같아 백엔드와 화면은 모드를 모른다. 실패는 종류에 따라 나눈다.
- 일시 장애(429, 5xx, 연결 실패, 시간 초과)나 크레딧 소진(402)이고 예비 제공자(LLM_FALLBACK_PROVIDER)가 있으며 시간이 남았으면:
  질문을 처음부터 예비 모델로 다시 푼다. 한 번만 넘기고, 마감 시각은 그대로다.
  도중의 모델 호출 하나만 바꾸지 않는다(LangChain ModelFallbackMiddleware 방식). 질문 하나는 한 모델이 끝까지 풀어
  모델마다 잰 품질과 운영이 같고, 넘긴 뒤에는 그 질문 안에서 주 제공자의 실패를 다시 기다리지 않는다.
  주 제공자가 연달아 못 쓰는 오류를 내면 차단기(breaker.py, circuitbreaker 라이브러리)가 열려 한동안 처음부터 예비 제공자로 보낸다.
- 그 밖의 상류 장애(키 오류, 잘못된 요청 등)나 예비도 실패: 고정 흐름도 같은 제공자를 부르므로 다시 시도하지 않고 502.
- 시간 초과, 토큰 예산 초과: 더 부르지 않고 정해진 대체 답변.
- 루프의 논리 실패(모델 호출 상한, 빈 답, 잘림, 단계 상한): 남은 시간이 있으면 고정 흐름, 없으면 대체 답변.
  예비 모델로 넘어간 뒤라면 고정 흐름도 예비 제공자로 부른다.
"""

import logging
import time

from fastapi import HTTPException, status

from app.chatbot.agent.breaker import CircuitBreakerError, PrimaryUnavailable, guarded
from app.chatbot.agent.graph import (
    DEADLINE_SECONDS,
    STOP_DEADLINE,
    STOP_TOKEN_BUDGET,
    AgentContext,
    AgentLedger,
    AgentRun,
    run_agent,
)
from app.chatbot.agent.models import (
    chat_model,
    fallback_chat_model,
    fallback_provider_name,
    forced_tool_call_settings,
    is_provider_unavailable,
    model_call_settings,
    provider_name,
    reasoning_level,
)
from app.chatbot.agent.similar import SIMILAR_FALLBACK, asks_for_similar, unknown_notice_numbers, with_similar_list
from app.chatbot.agent.tools import FIND_SIMILAR_ANIMALS, ToolContext
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
    get_chatbot_provider,
)
from app.chatbot.service import (
    answer_fixed,
    chatbot_response,
    agent_health_retriever,
    find_health_evidence,
    health_retriever,
    log_health_answer,
    provider_or_500,
)

logger = logging.getLogger("uvicorn.error")

# 고정 흐름은 모델을 한 번 부른다. 남은 시간이 이보다 적으면 부르지 않고 대체 답변으로 끝낸다.
FIXED_FLOW_MIN_SECONDS = 5.0
# 예비 모델로 질문을 처음부터 다시 풀려면 남아 있어야 하는 시간. 11/12 GPT-6 Luna(medium) 60문항 5번 반복에서
# 질문 하나의 시간 중앙값이 4.7초, 90%가 6.5초였다. 이보다 적게 남으면 넘기지 않는다(넘겨도 마감에 끊긴다).
FALLBACK_PROVIDER_MIN_SECONDS = 7.0
STOP_TRUNCATED = "truncated"
# 고정 흐름으로 넘기지 않는 멈춤. 시간이 없거나, 이미 예산을 넘겨 더 부르면 안 된다.
NO_FIXED_FLOW_STOPS = {STOP_DEADLINE, STOP_TOKEN_BUDGET}

PATH_AGENT = "agent"
PATH_FIXED = "fixed"
PATH_FALLBACK = "fallback"
PATH_ERROR = "error"
# 주 제공자를 쓸 수 없어(일시 장애, 크레딧 소진) 예비 제공자로 넘긴 시도. 예비 시도는 자기 결과로 한 줄 더 남긴다.
PATH_SWITCH = "switch"
# 차단기가 열려 주 제공자를 부르지 않고 바로 예비 제공자로 보낸 질문. 예비 시도는 자기 결과로 한 줄 더 남긴다.
PATH_SKIP = "skip"
STOP_BREAKER_OPEN = "breaker_open"


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

    model_provider = provider_name()
    # 예비 제공자가 없으면 차단기도 쓰지 않는다(건너뛸 곳이 없다).
    backup = backup_provider()
    context = agent_context(req, model, model_provider, deadline, matches, evidence)
    skipped = False
    if backup is None:
        run, error = await run_once(prompt, context)
    else:
        try:
            run, error = await guarded(run_once)(prompt, context)
        except CircuitBreakerError:
            skipped = True
        except PrimaryUnavailable as exc:
            run, error = exc.run, exc.error
    if skipped:
        log_agent_run(PATH_SKIP, STOP_BREAKER_OPEN, AgentLedger(), started, model_provider)
        model_provider = fallback_provider_name()
        provider, model = backup
        context = agent_context(req, model, model_provider, deadline, matches, evidence)
        run, error = await run_once(prompt, context)
    elif error is not None and backup is not None and should_switch(error, deadline):
        log_agent_run(PATH_SWITCH, context.ledger.stop or "upstream", context.ledger, started, model_provider,
                      error=error)
        model_provider = fallback_provider_name()
        provider, model = backup
        # 근거와 도구 상태도 새로 시작한다. 실패한 시도가 도구로 더 찾은 근거는 검사를 거치지 않았다.
        context = agent_context(req, model, model_provider, deadline, matches, evidence)
        run, error = await run_once(prompt, context)
    ledger = context.ledger
    tools = context.tools

    if error is not None and not isinstance(error, (ChatbotProviderTimeoutError, TimeoutError)):
        log_agent_run(PATH_ERROR, "upstream", ledger, started, model_provider, error=error)
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(error)) from error
    stop = ledger.stop

    if stop is None:
        response = finish_agent_answer(
            provider.name,
            run,
            animal.species,
            req.shelterContext,
            tools.similar_notice_numbers,
            tools.similar_lines,
        )
        log_agent_run(PATH_AGENT, None, ledger, started, model_provider)
        return response

    remaining = deadline - time.monotonic()
    if stop not in NO_FIXED_FLOW_STOPS and remaining >= FIXED_FLOW_MIN_SECONDS:
        try:
            response = await answer_fixed(provider, req, matches, evidence, timeout=remaining)
        except HTTPException as exc:
            if not isinstance(exc.__cause__, ChatbotProviderTimeoutError):
                log_agent_run(PATH_ERROR, stop, ledger, started, model_provider, error=exc.__cause__)
                raise
        else:
            log_agent_run(PATH_FIXED, stop, ledger, started, model_provider)
            return response

    # 대체 답변. 루프가 더 찾은 근거는 검사를 거치지 않았으므로 처음 찾은 해설만 카드로 보낸다.
    log_health_answer(matches, [], True)
    log_agent_run(PATH_FALLBACK, stop, ledger, started, model_provider)
    cards = health_cards(matches, animal.species) if evidence else []
    return chatbot_response(provider.name, FALLBACK_ANSWER if evidence else NO_EVIDENCE_FALLBACK, [], cards)


def agent_context(req, model, model_provider: str, deadline: float, matches, evidence) -> AgentContext:
    """시도 하나의 상태. 예비 모델로 다시 풀 때는 새로 만들어 호출 수, 토큰, 도구 결과를 처음부터 센다."""
    tools = ToolContext(
        agent_health_retriever(),
        req.animalContext.specialMark,
        req.animalContext.species,
        matches=list(matches),
        evidence=list(evidence),
        animal_id=req.animalId,
        question=req.question,
        # 공고 id가 없으면 도구가 찾지 못하므로 강제하지 않는다.
        first_tool=FIND_SIMILAR_ANIMALS.name if req.animalId and asks_for_similar(req.question) else None,
    )
    return AgentContext(
        model,
        tools,
        deadline,
        model_settings=model_call_settings(model_provider),
        forced_tool_settings=forced_tool_call_settings(model_provider),
    )


async def run_once(prompt: str, context: AgentContext) -> tuple[AgentRun | None, Exception | None]:
    """루프를 한 번 돌린다. 제공자 장애(상류 오류, 시간 초과)는 올리지 않고 돌려준다. 잘림은 멈춘 이유로 남긴다."""
    try:
        return await run_agent(prompt, context), None
    except (ChatbotProviderTimeoutError, TimeoutError) as exc:
        context.ledger.stop = STOP_DEADLINE
        return None, exc
    except ChatbotProviderTruncatedError:
        context.ledger.stop = STOP_TRUNCATED
        return None, None
    except ChatbotProviderUpstreamError as exc:
        return None, exc


def should_switch(error: Exception, deadline: float) -> bool:
    """예비 제공자로 넘길까. 예비 제공자가 있는지는 부른 쪽이 본다."""
    return deadline - time.monotonic() >= FALLBACK_PROVIDER_MIN_SECONDS and is_provider_unavailable(error)


def backup_provider():
    """예비 제공자(고정 흐름용)와 예비 모델. 예비 설정이 없으면 None.

    설정은 기동에서 검사하므로 여기서 실패할 일은 거의 없다. 실패하면 예비 없이 원래 오류로 끝낸다.
    """
    name = fallback_provider_name()
    if not name:
        return None
    try:
        return get_chatbot_provider(name), fallback_chat_model()
    except ChatbotProviderConfigurationError as exc:
        logger.warning("Chatbot agent: fallback provider unavailable provider=%s error=%s", name, type(exc).__name__)
        return None


def finish_agent_answer(
    provider_name: str,
    run: AgentRun,
    species: str | None,
    shelter,
    similar_notice_numbers: set[str] | frozenset[str] = frozenset(),
    similar_lines: list[str] | tuple[str, ...] = (),
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
    answer = strip_citations(checked.answer)
    # 비슷한 동물 목록은 검사를 모두 통과한 답에만 붙인다. 대체 답에는 붙이지 않는다.
    if not checked.fallback:
        answer = with_similar_list(answer, list(similar_lines))
    return chatbot_response(provider_name, answer, sources, cards)


def log_agent_run(
    path: str, stop: str | None, ledger: AgentLedger, started: float, model_provider: str = "-", error=None
) -> None:
    """에이전트가 어떻게 끝났는지 숫자로 남긴다. 질문, 검색어, 답 내용은 넣지 않는다.

    provider는 그 시도의 모델 제공자다. 예비로 넘기면 주 제공자 줄(path=switch)과 예비 제공자 줄이 함께 남는다.

    토큰은 루프 안 모델 호출의 합이다(고정 흐름으로 넘어간 호출은 Gemini 응답 로그에 따로 남는다).
    error는 상류 장애와 우리 코드 문제를 로그에서 나누려고 오류 종류 이름만 남긴다.
    """
    logger.info(
        "Chatbot agent: path=%s stop=%s error=%s model_calls=%d tool_calls=%d tool_errors=%d "
        "input_tokens=%d output_tokens=%d reasoning_tokens=%d reasoning=%s elapsed_ms=%d provider=%s",
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
        model_provider,
    )


def _error_name(error) -> str:
    """ChatbotProviderUpstreamError는 원인(제공자 오류) 종류를 남긴다. 예: GoogleRateLimitError."""
    if error is None:
        return "-"
    cause = error.__cause__ if isinstance(error, ChatbotProviderUpstreamError) and error.__cause__ else error
    return type(cause).__name__
