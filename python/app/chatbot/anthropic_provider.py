"""고정 흐름(CHATBOT_MODE=rag, 에이전트의 비상 경로)에서 Claude를 한 번 부른다.

gemini_provider.py, openai_provider.py와 같은 규칙을 따른다: 일시 오류(5xx, 과부하 529, 연결 실패)만 한 번 더
보내고, 429(요청 한도), 시간 초과, 400(요청 잘못)은 바로 실패한다.
"""

import asyncio
import logging
import time

import httpx

from app.chatbot.agent.models import ANSWER_JSON_SCHEMA
from app.chatbot.provider import (
    ChatbotProvider,
    ChatbotProviderConfigurationError,
    ChatbotProviderTimeoutError,
    ChatbotProviderTruncatedError,
    ChatbotProviderUpstreamError,
)
from app.settings import ChatbotSettings

logger = logging.getLogger("uvicorn.error")

MESSAGES_URL = "https://api.anthropic.com/v1/messages"
API_VERSION = "2023-06-01"
MAX_ATTEMPTS = 2
RETRY_DELAY_SECONDS = 1.0
# 고정 흐름은 모델을 한 번 불러 바로 답하므로 생각을 끈다(Gemini, OpenAI 고정 흐름과 같은 출력 상한).
MAX_OUTPUT_TOKENS = 1024


def _is_transient(exc: httpx.HTTPError) -> bool:
    if isinstance(exc, httpx.HTTPStatusError):
        return exc.response.status_code >= 500
    return isinstance(exc, (httpx.ConnectError, httpx.RemoteProtocolError))


def _log_failure(exc: httpx.HTTPError, attempt: int, started: float, retry: bool) -> None:
    """실패를 숫자로 남긴다. 질문과 응답 내용, 키는 넣지 않는다."""
    status = exc.response.status_code if isinstance(exc, httpx.HTTPStatusError) else "-"
    logger.warning(
        "Anthropic request failed: attempt=%d status=%s error=%s elapsed_ms=%d retry=%s",
        attempt,
        status,
        type(exc).__name__,
        (time.monotonic() - started) * 1000,
        str(retry).lower(),
    )


def _provider_error(exc: httpx.HTTPError) -> Exception:
    if isinstance(exc, httpx.TimeoutException):
        return ChatbotProviderTimeoutError("Anthropic request timed out")
    if isinstance(exc, httpx.HTTPStatusError):
        return ChatbotProviderUpstreamError(f"Anthropic request failed with status {exc.response.status_code}")
    return ChatbotProviderUpstreamError("Anthropic request failed")


class AnthropicChatbotProvider(ChatbotProvider):
    name = "anthropic"

    def __init__(self):
        settings = ChatbotSettings.load()
        self.api_key = settings.anthropic_api_key.strip()
        self.model = settings.anthropic_model.strip() or ChatbotSettings.model_fields["anthropic_model"].default
        self.timeout_seconds = settings.anthropic_timeout_seconds

    async def generate_answer(self, prompt: str, json_output: bool = False, timeout: float | None = None) -> str:
        payload = {
            "model": self.model,
            "max_tokens": MAX_OUTPUT_TOKENS,
            "messages": [{"role": "user", "content": prompt}],
            "thinking": {"type": "disabled"},
        }
        if json_output:
            payload["output_config"] = {"format": {"type": "json_schema", "schema": ANSWER_JSON_SCHEMA}}

        response_body = await self._post(payload, timeout)
        answer = self._extract_answer(response_body)
        if not answer:
            raise ChatbotProviderUpstreamError("Anthropic response did not include answer text")
        return answer

    async def _post(self, payload: dict, timeout: float | None) -> dict:
        if not self.api_key:
            raise ChatbotProviderConfigurationError("ANTHROPIC_API_KEY is not configured")

        timeout_seconds = self.timeout_seconds if timeout is None else max(0.1, min(self.timeout_seconds, timeout))
        # 남은 시간을 받은 호출(에이전트의 비상 경로)은 시간이 정해져 있어 다시 시도하지 않는다.
        attempts = 1 if timeout is not None else MAX_ATTEMPTS
        async with httpx.AsyncClient(timeout=timeout_seconds) as client:
            for attempt in range(1, attempts + 1):
                started = time.monotonic()
                try:
                    response = await client.post(
                        MESSAGES_URL,
                        headers={
                            "x-api-key": self.api_key,
                            "anthropic-version": API_VERSION,
                            "content-type": "application/json",
                        },
                        json=payload,
                    )
                    response.raise_for_status()
                    break
                except httpx.HTTPError as exc:
                    retry = attempt < attempts and _is_transient(exc)
                    _log_failure(exc, attempt, started, retry)
                    if retry:
                        await asyncio.sleep(RETRY_DELAY_SECONDS)
                        continue
                    raise _provider_error(exc) from exc

        try:
            response_body = response.json()
        except ValueError as exc:
            raise ChatbotProviderUpstreamError("Anthropic response was not valid JSON") from exc

        self._log_response_metadata(response_body)
        self._raise_if_not_finished(response_body)
        return response_body

    @staticmethod
    def _raise_if_not_finished(response_body: dict) -> None:
        reason = response_body.get("stop_reason")
        if reason == "max_tokens":
            raise ChatbotProviderTruncatedError("Anthropic response was truncated by token limit")
        # refusal: 안전 분류기가 답을 막았다. 답 글이 없거나 중간에 끊겨 있어 쓰지 않는다.
        if reason == "refusal":
            raise ChatbotProviderUpstreamError("Anthropic response was refused")

    @staticmethod
    def _log_response_metadata(response_body: dict) -> None:
        usage = response_body.get("usage") or {}
        logger.info(
            "Anthropic response metadata: stopReason=%s inputTokens=%s outputTokens=%s",
            response_body.get("stop_reason"),
            usage.get("input_tokens"),
            usage.get("output_tokens"),
        )

    @staticmethod
    def _extract_answer(response_body: dict) -> str:
        texts = [
            part["text"]
            for part in response_body.get("content") or []
            if isinstance(part, dict) and part.get("type") == "text" and part.get("text")
        ]
        return "".join(texts).strip()
