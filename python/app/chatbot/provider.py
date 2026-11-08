from abc import ABC, abstractmethod

from app.settings import ChatbotSettings


class ChatbotProvider(ABC):
    name: str

    @abstractmethod
    async def generate_answer(self, prompt: str, json_output: bool = False) -> str:
        """json_output이 참이면 JSON 하나로 답하도록 요청한다(지원하지 않는 제공자는 무시해도 된다)."""
        raise NotImplementedError


class ChatbotProviderConfigurationError(RuntimeError):
    pass


class ChatbotProviderUpstreamError(RuntimeError):
    pass


class NotImplementedChatbotProvider(ChatbotProvider):
    def __init__(self, name: str):
        self.name = name

    async def generate_answer(self, prompt: str, json_output: bool = False) -> str:
        raise NotImplementedError(f"LLM provider '{self.name}' is not implemented in Step 1")


def get_chatbot_provider() -> ChatbotProvider:
    provider_name = ChatbotSettings.load().llm_provider.strip().lower() or "stub"
    if provider_name == "stub":
        from app.chatbot.stub_provider import StubChatbotProvider

        return StubChatbotProvider()
    if provider_name == "gemini":
        from app.chatbot.gemini_provider import GeminiChatbotProvider

        return GeminiChatbotProvider()
    if provider_name == "openai":
        return NotImplementedChatbotProvider(provider_name)
    raise RuntimeError(f"Unsupported LLM_PROVIDER: {provider_name}")
