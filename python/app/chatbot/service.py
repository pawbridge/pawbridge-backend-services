from fastapi import HTTPException, status

from app.chatbot.prompt_builder import build_prompt
from app.chatbot.provider import (
    ChatbotProviderConfigurationError,
    ChatbotProviderUpstreamError,
    get_chatbot_provider,
)

SAFETY_NOTICE = (
    "이 답변은 일반적인 참고 정보이며, 정확한 건강 상태나 치료 판단은 "
    "동물병원 또는 수의사에게 확인해 주세요."
)


class ChatbotService:
    async def create_message(self, req):
        try:
            provider = get_chatbot_provider()
            prompt = build_prompt(req.animalContext, req.recentMessages, req.question)
            answer = await provider.generate_answer(prompt)
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
        return {
            "answer": answer,
            "safetyNotice": SAFETY_NOTICE,
            "provider": provider.name,
        }
