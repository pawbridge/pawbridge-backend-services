from app.chatbot.provider import ChatbotProvider

STUB_ANSWER = (
    "현재 공고 정보를 기준으로 보호소에 건강 상태, 예방접종 여부, "
    "중성화 여부, 성격과 생활 습관을 확인해 보세요."
)


class StubChatbotProvider(ChatbotProvider):
    name = "stub"

    async def generate_answer(self, prompt: str, json_output: bool = False, timeout: float | None = None) -> str:
        return STUB_ANSWER
