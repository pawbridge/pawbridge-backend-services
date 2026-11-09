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

    async def generate_turn(self, messages: list, tools: list, allow_tools: bool, timeout: float):
        """에이전트의 모델 호출 한 번. 대화(langchain_core 메시지)를 받아 AIMessage 하나를 돌려준다.

        AIMessage.tool_calls가 있으면 도구를 부른 것이고, 없으면 content가 최종 답이다.
        allow_tools가 거짓이면 도구 선언은 그대로 두고 도구 호출만 막는다(호출 상한에 닿았을 때).
        도구 호출을 지원하지 않는 제공자는 에이전트 모드에서 설정 오류가 된다.
        """
        raise ChatbotProviderConfigurationError(f"LLM provider '{self.name}' does not support agent tool calls")


class ChatbotProviderConfigurationError(RuntimeError):
    pass


class ChatbotProviderUpstreamError(RuntimeError):
    pass


class ChatbotProviderTruncatedError(ChatbotProviderUpstreamError):
    """답이 길이 상한에 걸려 잘렸다. 근거 답변에서는 대체 답변으로 바꿀 수 있다."""


class ChatbotProviderTimeoutError(ChatbotProviderUpstreamError):
    """제공자가 시간 안에 답하지 않았다. 고정 흐름은 다른 상류 오류처럼 502, 에이전트는 대체 답변으로 끝낸다."""


class NotImplementedChatbotProvider(ChatbotProvider):
    def __init__(self, name: str):
        self.name = name

    async def generate_answer(self, prompt: str, json_output: bool = False, timeout: float | None = None) -> str:
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
