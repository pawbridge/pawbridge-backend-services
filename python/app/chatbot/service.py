import logging
from functools import lru_cache

from fastapi import HTTPException, status

from app.chatbot.health.answer import (
    FALLBACK_ANSWER,
    build_evidence,
    check_answer,
    check_plain_answer,
    health_cards,
    sources_for,
    strip_citations,
)
from app.chatbot.health.knowledge import entries_version, load_entries
from app.chatbot.health.retriever import HealthMatch, RuleHealthRetriever
from app.chatbot.prompt_builder import build_prompt
from app.chatbot.provider import (
    ChatbotProviderConfigurationError,
    ChatbotProviderTruncatedError,
    ChatbotProviderUpstreamError,
    get_chatbot_provider,
)
from app.settings import ChatbotSettings

SAFETY_NOTICE = (
    "이 답변은 일반적인 참고 정보이며, 정확한 건강 상태나 치료 판단은 "
    "동물병원 또는 수의사에게 확인해 주세요."
)

# uvicorn 로그와 같은 출력으로 나가야 운영 로그(Loki)에 모인다(gemini_provider와 같은 로거).
logger = logging.getLogger("uvicorn.error")


@lru_cache(maxsize=1)
def health_retriever() -> RuleHealthRetriever:
    """승인된 해설로 규칙 검색기를 한 번만 만든다. 해설이 잘못됐거나 하나도 없으면 실패한다."""
    entries = load_entries()
    if not entries:
        raise RuntimeError("HEALTH_RAG_ENABLED인데 승인된 건강 해설이 하나도 없습니다")
    return RuleHealthRetriever(entries)


@lru_cache(maxsize=1)
def health_knowledge_version() -> str:
    return entries_version()


def log_health_answer(matches: list[HealthMatch], used: list[int], fallback: bool) -> None:
    """어떤 해설을 찾았고 그중 몇 번 근거를 썼는지 남긴다. 질문과 답 내용은 대화 기록에 있으므로 넣지 않는다."""
    logger.info(
        "Health RAG answer: entries=%s used=%s fallback=%s knowledge=%s",
        ",".join(f"{match.entry.id}:{match.basis}" for match in matches) or "-",
        ",".join(str(number) for number in used) or "-",
        str(fallback).lower(),
        health_knowledge_version(),
    )


def load_health_retriever_if_enabled() -> None:
    """기동 때 부른다. 설정이 켜져 있으면 해설을 미리 읽어 잘못된 상태를 요청 전에 드러낸다."""
    if ChatbotSettings.load().health_rag_enabled:
        health_retriever()


class ChatbotService:
    async def create_message(self, req):
        settings = ChatbotSettings.load()
        animal = req.animalContext
        matches = []
        evidence = None
        if settings.health_rag_enabled:
            matches = health_retriever().search(animal.specialMark, req.question, animal.species)
            evidence = build_evidence(matches, animal.species)

        provider = None
        try:
            provider = get_chatbot_provider()
            prompt = build_prompt(animal, req.recentMessages, req.question, evidence)
            answer = await provider.generate_answer(prompt, json_output=bool(evidence))
        except ChatbotProviderConfigurationError as exc:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail=str(exc),
            ) from exc
        except ChatbotProviderTruncatedError as exc:
            # 근거 답변이 잘렸으면 오류 대신 대체 답변을 보낸다.
            if not evidence:
                raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc)) from exc
            answer = None
        except ChatbotProviderUpstreamError as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=str(exc),
            ) from exc

        sources = []
        cards = []
        if evidence:
            checked = check_answer(answer, evidence, expect_json=True) if answer is not None else None
            answer = checked.answer if checked else FALLBACK_ANSWER
            sources = sources_for(checked.used, evidence) if checked else []
            cards = health_cards(matches, animal.species)
            log_health_answer(matches, checked.used if checked else [], checked.fallback if checked else True)
            answer = strip_citations(answer)
        elif evidence is not None:
            # 설정은 켜졌지만 근거를 찾지 못한 답도 의학 표현을 검사한다.
            plain = check_plain_answer(answer)
            answer = plain.answer
            log_health_answer([], [], plain.fallback)

        return {
            "answer": answer,
            "safetyNotice": SAFETY_NOTICE,
            "provider": provider.name,
            "sources": sources,
            "healthCards": cards,
        }
