"""에이전트가 쓰는 채팅 모델을 설정으로 만든다. 제공자 이름이 나오는 곳은 이 파일뿐이다.

    LLM_PROVIDER, GEMINI_MODEL, GEMINI_API_KEY  → LangChain 채팅 모델(init_chat_model)
    LLM_PROVIDER=openai, OPENAI_MODEL, OPENAI_API_KEY  → 같은 방식의 OpenAI 채팅 모델
    LLM_PROVIDER=anthropic, ANTHROPIC_MODEL, ANTHROPIC_API_KEY  → 같은 방식의 Claude 채팅 모델
    CHATBOT_REASONING(minimal, low, medium, high) → 제공자별 추론 설정과 호출 하나의 출력 상한

에이전트 코드(graph.py, service.py)는 LangChain 공통 모양(메시지, usage_metadata, ModelError)만 본다.
제공자를 더하려면 _BUILDERS에 함수를 하나 넣고, 그 SDK의 오류를 classify_model_error가 알아보는지 시험한다.
"""

from functools import lru_cache

from app.chatbot.provider import (
    ChatbotProviderConfigurationError,
    ChatbotProviderTimeoutError,
    ChatbotProviderUpstreamError,
)
from app.chatbot.stub_provider import STUB_ANSWER
from app.settings import ChatbotSettings

# 추론 단계별 호출 하나의 출력 상한(생각 토큰 포함). minimal은 11/08 평가의 실제 답 길이(최대 408자)를
# 근거로 한 값이고, 나머지는 생각 토큰을 재기 전의 시작값이다. 답 글은 어차피 답 검사가 900자에서 자른다.
MAX_OUTPUT_TOKENS = {"minimal": 1024, "low": 2048, "medium": 4096, "high": 8192}
TEMPERATURE = 0.4


def reasoning_level() -> str:
    return ChatbotSettings.load().chatbot_reasoning


@lru_cache(maxsize=1)
def chat_model():
    """설정으로 모델을 한 번만 만든다. 설정이 잘못됐으면 ChatbotProviderConfigurationError."""
    settings = ChatbotSettings.load()
    provider = settings.llm_provider.strip().lower() or "stub"
    builder = _BUILDERS.get(provider)
    if builder is None:
        raise ChatbotProviderConfigurationError(f"LLM provider '{provider}' does not support the agent")
    return builder(settings, settings.chatbot_reasoning)


def _gemini(settings: ChatbotSettings, reasoning: str):
    from langchain.chat_models import init_chat_model

    api_key = settings.gemini_api_key.strip()
    if not api_key:
        raise ChatbotProviderConfigurationError("GEMINI_API_KEY is not configured")
    model = settings.gemini_model.strip() or ChatbotSettings.model_fields["gemini_model"].default
    return init_chat_model(
        model,
        model_provider="google_genai",
        google_api_key=api_key,
        # Gemini 3 이상의 추론 단계. 2.5 이하 모델은 thinking_budget을 쓰므로 그때 여기서 나눈다.
        thinking_level=reasoning,
        max_output_tokens=MAX_OUTPUT_TOKENS[reasoning],
        # 최종 답은 늘 JSON({"answer", "used"})이다. 도구 선언과 같이 쓸 수 있음을 11/10에 확인했다.
        response_mime_type="application/json",
        # 패키지 기본 재시도는 끈다. 1이 "한 번만"이다(0은 구글 기본값 5회 재시도이고 429도 다시 보낸다).
        # 일시 오류 재시도는 model_call_settings()가 요청마다 따로 넘긴다.
        max_retries=1,
        timeout=settings.gemini_timeout_seconds,
        # gemini-3.5-flash-lite처럼 표집 값을 고정한 모델은 패키지가 이 값을 보내지 않는다.
        temperature=TEMPERATURE,
    )


# OpenAI 추론 단계 이름. gpt-6-luna는 minimal이 없고 none, low, medium, high를 받는다(11/11 확인).
OPENAI_REASONING_EFFORT = {"minimal": "none", "low": "low", "medium": "medium", "high": "high"}


def _openai(settings: ChatbotSettings, reasoning: str):
    from langchain.chat_models import init_chat_model

    api_key = settings.openai_api_key.strip()
    if not api_key:
        raise ChatbotProviderConfigurationError("OPENAI_API_KEY is not configured")
    model = settings.openai_model.strip() or ChatbotSettings.model_fields["openai_model"].default
    return init_chat_model(
        model,
        model_provider="openai",
        api_key=api_key,
        # gpt-6-luna는 Chat Completions에서 도구와 추론 단계를 함께 받지 않는다(400, 11/11 확인).
        # Responses API는 도구, 추론 단계, JSON 형식을 함께 받는다.
        use_responses_api=True,
        reasoning={"effort": OPENAI_REASONING_EFFORT[reasoning]},
        max_tokens=MAX_OUTPUT_TOKENS[reasoning],
        # 최종 답은 늘 JSON({"answer", "used"})이다. 도구 강제 호출과도 같이 쓸 수 있다(11/11 확인).
        model_kwargs={"text": {"format": {"type": "json_object"}}},
        # 질문과 답을 OpenAI 쪽에 저장하지 않는다. 저장하지 않으면 다음 호출에 생각 내용을 넘길 수 없으므로
        # 암호화된 생각 내용을 받아 그대로 돌려준다.
        store=False,
        include=["reasoning.encrypted_content"],
        # SDK 재시도 한 번. Gemini와 달리 429와 시간 초과도 한 번 더 보낸다(SDK가 상태 코드별로 고르지 못함).
        # OpenAI의 429는 분당 요청 한도처럼 곧 풀리는 경우가 많고, 실패한 요청은 요금이 없다.
        max_retries=1,
        timeout=settings.openai_timeout_seconds,
        # temperature는 보내지 않는다. gpt-6-luna는 기본값(1) 말고는 400으로 거절한다(11/11 확인).
    )


# 최종 답 JSON의 모양. Anthropic은 "JSON이면 된다" 모드가 없고 스키마를 받는다.
# 답 검사(health/answer.py)가 받는 모양과 같다: 글 하나와 쓴 근거 번호 목록.
ANSWER_JSON_SCHEMA = {
    "type": "object",
    "properties": {
        "answer": {"type": "string"},
        "used": {"type": "array", "items": {"type": "integer"}},
    },
    "required": ["answer", "used"],
    "additionalProperties": False,
}


def anthropic_output_config(reasoning: str, json_output: bool = True) -> dict:
    """Claude의 추론 단계와 답 형식. minimal은 Claude에 없어 low로 보내고 생각을 끈다(anthropic_thinking)."""
    config = {"effort": "low" if reasoning == "minimal" else reasoning}
    if json_output:
        config["format"] = {"type": "json_schema", "schema": ANSWER_JSON_SCHEMA}
    return config


def anthropic_thinking(reasoning: str) -> dict | None:
    """minimal만 생각을 끈다. 나머지는 보내지 않는다(모델 기본값인 적응형 생각).

    생각을 명시적으로 켜면(adaptive) langchain-anthropic이 도구 강제(any, 도구 이름)를 경고만 남기고 지운다.
    API는 Haiku 5.5에서 도구 강제와 기본 생각을 함께 받는다(11/12 확인).
    """
    return {"type": "disabled"} if reasoning == "minimal" else None


def _anthropic(settings: ChatbotSettings, reasoning: str):
    from langchain_anthropic import ChatAnthropic

    class PawbridgeChatAnthropic(ChatAnthropic):
        def bind_tools(self, tools, *, tool_choice=None, **kwargs):
            # 패키지는 문자열 "none"을 none이라는 도구를 부르라는 뜻({"type": "tool", "name": "none"})으로
            # 바꿔 400을 받는다. 그래프는 마지막 호출에서 "none"으로 도구를 막으므로 API 모양으로 바꾼다.
            if tool_choice == "none":
                tool_choice = {"type": "none"}
            return super().bind_tools(tools, tool_choice=tool_choice, **kwargs)

    api_key = settings.anthropic_api_key.strip()
    if not api_key:
        raise ChatbotProviderConfigurationError("ANTHROPIC_API_KEY is not configured")
    model = settings.anthropic_model.strip() or ChatbotSettings.model_fields["anthropic_model"].default
    options = {}
    thinking = anthropic_thinking(reasoning)
    if thinking is not None:
        options["thinking"] = thinking
    return PawbridgeChatAnthropic(
        model=model,
        api_key=api_key,
        max_tokens=MAX_OUTPUT_TOKENS[reasoning],
        # 추론 단계와 최종 답 JSON 스키마. 도구 강제 호출과도 같이 쓸 수 있다(11/12 확인).
        output_config=anthropic_output_config(reasoning),
        # SDK 재시도 한 번(0이 재시도 없음). 429, 5xx, 연결 실패, 시간 초과를 다시 보낸다.
        # Start 등급 한도(분당 4,000회)라 429는 순간 몰림일 때만 나고, 실패한 요청은 요금이 없다.
        max_retries=1,
        timeout=settings.anthropic_timeout_seconds,
        # temperature는 보내지 않는다. Haiku 5.5는 기본값 말고는 400으로 거절한다.
        **options,
    )


# Gemini 일시 오류(5xx)만 한 번 더 보낸다. 고정 흐름(gemini_provider.py)과 같은 규칙이다:
# 429(요청 한도)와 시간 초과는 다시 보내도 같거나 한도만 깎으므로 바로 실패한다.
# 11/11 시범 실행에서 재시도가 없던 에이전트는 첫 시도 실패가 8/60, 같은 시간대 고정 흐름은 1/60이었다.
GEMINI_RETRY_ATTEMPTS = 2
GEMINI_RETRY_DELAY_SECONDS = 1.0
GEMINI_RETRY_STATUS_CODES = [500, 502, 503, 504]


@lru_cache(maxsize=1)
def model_call_settings() -> dict:
    """모델 호출마다 붙일 제공자별 설정. 제공자 이름이 나오는 곳이 이 파일뿐이도록 여기서 만든다."""
    provider = ChatbotSettings.load().llm_provider.strip().lower() or "stub"
    if provider != "gemini":
        return {}

    from google.genai.types import HttpOptions, HttpRetryOptions

    retry = HttpRetryOptions(
        attempts=GEMINI_RETRY_ATTEMPTS,
        initial_delay=GEMINI_RETRY_DELAY_SECONDS,
        http_status_codes=GEMINI_RETRY_STATUS_CODES,
    )
    # 요청별 http_options는 모델 설정에서 나온 시간 제한을 남기고 재시도만 덮어쓴다.
    return {"http_options": HttpOptions(retry_options=retry)}


@lru_cache(maxsize=1)
def forced_tool_call_settings() -> dict:
    """도구 호출을 강제하는 호출에 더 붙일 제공자별 설정.

    Gemini는 도구 강제(ANY 모드)와 JSON 응답 형식을 함께 받지 않는다(400 "Forced function calling (ANY mode)
    with a response mime type: 'application/json' is unsupported", 11/11 확인). 강제한 호출은 도구 호출만
    돌려주고 답 글을 쓰지 않으므로 그 호출에서만 JSON 형식을 뺀다.
    """
    provider = ChatbotSettings.load().llm_provider.strip().lower() or "stub"
    if provider != "gemini":
        return {}
    return {"response_mime_type": "text/plain"}


def _stub(settings: ChatbotSettings, reasoning: str):
    """키 없이 로컬에서 에이전트 모드를 돌려 볼 때 쓴다. 도구를 부르지 않고 고정 문구로 답한다."""
    import json

    from langchain_core.language_models.chat_models import BaseChatModel
    from langchain_core.messages import AIMessage
    from langchain_core.outputs import ChatGeneration, ChatResult

    answer = json.dumps({"answer": STUB_ANSWER, "used": []}, ensure_ascii=False)

    class StubChatModel(BaseChatModel):
        @property
        def _llm_type(self) -> str:
            return "stub"

        def bind_tools(self, tools, **kwargs):
            return self

        def _generate(self, messages, stop=None, run_manager=None, **kwargs) -> ChatResult:
            return ChatResult(generations=[ChatGeneration(message=AIMessage(content=answer))])

    return StubChatModel()


_BUILDERS = {
    "gemini": _gemini,
    "openai": _openai,
    "anthropic": _anthropic,
    "stub": _stub,
}


def classify_model_error(exc: BaseException) -> Exception | None:
    """모델 호출 오류를 기존 제공자 오류로 바꾼다. 우리 코드의 버그 같은 그 밖의 오류는 None(그대로 올림).

    - 시간 초과: ChatbotProviderTimeoutError(에이전트는 대체 답변)
    - 상류 장애(429, 5xx, 연결 실패, 그 밖의 제공자 거절): ChatbotProviderUpstreamError(고정 흐름처럼 502)
    메시지에는 오류 종류 이름만 넣는다. 제공자 오류 문구에 요청 내용이 섞일 수 있어서다.
    """
    import httpx
    from langchain_core.exceptions import ModelError, ModelTimeoutError

    name = type(exc).__name__
    if isinstance(exc, (ModelTimeoutError, httpx.TimeoutException, TimeoutError)) or _is_provider_sdk_timeout(exc):
        return ChatbotProviderTimeoutError(f"Model request timed out ({name})")
    if isinstance(exc, (ModelError, httpx.HTTPError)) or _is_provider_sdk_error(exc):
        return ChatbotProviderUpstreamError(f"Model request failed ({name})")
    return None


def _is_provider_sdk_timeout(exc: BaseException) -> bool:
    """LangChain 공통 오류로 바뀌지 않은 제공자 SDK의 시간 초과. OpenAI와 Anthropic SDK의 시간 초과는 연결 오류의 하위
    종류라 따로 보지 않으면 상류 장애로 분류된다. 이미 불러온 SDK만 본다."""
    import sys

    if "openai" in sys.modules:
        from openai import APITimeoutError

        if isinstance(exc, APITimeoutError):
            return True
    if "anthropic" in sys.modules:
        from anthropic import APITimeoutError as AnthropicTimeoutError

        if isinstance(exc, AnthropicTimeoutError):
            return True
    return False


def _is_provider_sdk_error(exc: BaseException) -> bool:
    """LangChain 공통 오류로 바뀌지 않은 제공자 SDK 오류(예: Gemini의 409, OpenAI와 Anthropic의 409). 이미 불러온 SDK만 본다."""
    import sys

    if "google.genai" in sys.modules:
        from google.genai.errors import APIError

        if isinstance(exc, APIError):
            return True
    if "langchain_google_genai" in sys.modules:
        from langchain_google_genai.chat_models import ChatGoogleGenerativeAIError

        if isinstance(exc, ChatGoogleGenerativeAIError):
            return True
    if "openai" in sys.modules:
        from openai import APIError as OpenAIAPIError

        if isinstance(exc, OpenAIAPIError):
            return True
    if "anthropic" in sys.modules:
        from anthropic import APIError as AnthropicAPIError

        if isinstance(exc, AnthropicAPIError):
            return True
    return False
