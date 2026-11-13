from abc import ABC, abstractmethod

from app.settings import ChatbotSettings


class ChatbotProvider(ABC):
    name: str

    @abstractmethod
    async def generate_answer(self, prompt: str, json_output: bool = False, timeout: float | None = None) -> str:
        """json_output이 참이면 JSON 하나로 답하도록 요청한다(지원하지 않는 제공자는 무시해도 된다).

        timeout을 주면 제공자 기본 시간 제한보다 짧을 때 그 값을 쓴다(에이전트의 남은 시간).
        """
        raise NotImplementedError


class ChatbotProviderConfigurationError(RuntimeError):
    pass


class ChatbotProviderUpstreamError(RuntimeError):
    pass


class ChatbotProviderTruncatedError(ChatbotProviderUpstreamError):
    """답이 길이 상한에 걸려 잘렸다. 근거 답변에서는 대체 답변으로 바꿀 수 있다."""


class ChatbotProviderTimeoutError(ChatbotProviderUpstreamError):
    """제공자가 시간 안에 답하지 않았다. 고정 흐름은 다른 상류 오류처럼 502, 에이전트는 대체 답변으로 끝낸다."""


def get_chatbot_provider(name: str | None = None) -> ChatbotProvider:
    """고정 흐름의 제공자. name을 비우면 LLM_PROVIDER이고, 에이전트의 예비 제공자는 이름을 넘긴다."""
    provider_name = (name or ChatbotSettings.load().llm_provider).strip().lower() or "stub"
    if provider_name == "stub":
        from app.chatbot.stub_provider import StubChatbotProvider

        return StubChatbotProvider()
    if provider_name == "gemini":
        from app.chatbot.gemini_provider import GeminiChatbotProvider

        return GeminiChatbotProvider()
    if provider_name == "openai":
        from app.chatbot.openai_provider import OpenAIChatbotProvider

        return OpenAIChatbotProvider()
    if provider_name == "anthropic":
        from app.chatbot.anthropic_provider import AnthropicChatbotProvider

        return AnthropicChatbotProvider()
    raise RuntimeError(f"Unsupported LLM_PROVIDER: {provider_name}")
