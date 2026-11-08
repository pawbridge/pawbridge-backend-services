from functools import lru_cache

from fastapi import HTTPException, status

from app.chatbot.health.answer import build_evidence, check_answer, health_cards, sources_for
from app.chatbot.health.knowledge import load_entries
from app.chatbot.health.retriever import RuleHealthRetriever
from app.chatbot.prompt_builder import build_prompt
from app.chatbot.provider import (
    ChatbotProviderConfigurationError,
    ChatbotProviderUpstreamError,
    get_chatbot_provider,
)
from app.settings import ChatbotSettings

SAFETY_NOTICE = (
    "이 답변은 일반적인 참고 정보이며, 정확한 건강 상태나 치료 판단은 "
    "동물병원 또는 수의사에게 확인해 주세요."
)


@lru_cache(maxsize=1)
def health_retriever() -> RuleHealthRetriever:
    """승인된 해설로 규칙 검색기를 한 번만 만든다. 해설 형식이 틀리면 여기서 실패한다."""
    return RuleHealthRetriever(load_entries())


class ChatbotService:
    async def create_message(self, req):
        settings = ChatbotSettings.load()
        animal = req.animalContext
        matches = []
        evidence = None
        if settings.health_rag_enabled:
            matches = health_retriever().search(animal.specialMark, req.question, animal.species)
            evidence = build_evidence(matches, animal.species)

        try:
            provider = get_chatbot_provider()
            prompt = build_prompt(animal, req.recentMessages, req.question, evidence)
            answer = await provider.generate_answer(prompt, json_output=bool(evidence))
        except ChatbotProviderConfigurationError as exc:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail=str(exc),
            ) from exc
        except ChatbotProviderUpstreamError as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=str(exc),
            ) from exc

        sources = []
        if evidence:
            checked = check_answer(answer, evidence)
            answer = checked.answer
            sources = sources_for(checked.used, evidence)

        return {
            "answer": answer,
            "safetyNotice": SAFETY_NOTICE,
            "provider": provider.name,
            "sources": sources,
            "healthCards": health_cards(matches, animal.species),
        }
