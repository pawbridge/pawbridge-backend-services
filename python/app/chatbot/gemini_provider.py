import asyncio
import logging
import time

import httpx

from app.chatbot.provider import (
    ChatbotProvider,
    ChatbotProviderConfigurationError,
    ChatbotProviderTimeoutError,
    ChatbotProviderTruncatedError,
    ChatbotProviderUpstreamError,
)
from app.settings import ChatbotSettings

logger = logging.getLogger("uvicorn.error")

# Gemini 일시 오류(5xx, 연결 실패)는 한 번만 다시 시도한다. 과부하는 몰려서 오므로 두 번째부터는 효과가 작고,
# 호출당 시간 제한(기본 15초)이 있어 두 번이면 백엔드가 기다리는 60초에 가까워진다.
# 429(요청 한도), 시간 초과, 400(요청 잘못)은 다시 보내도 같거나 한도만 깎으므로 바로 실패한다.
MAX_ATTEMPTS = 2
RETRY_DELAY_SECONDS = 1.0


def _is_transient(exc: httpx.HTTPError) -> bool:
    if isinstance(exc, httpx.HTTPStatusError):
        return exc.response.status_code >= 500
    return isinstance(exc, (httpx.ConnectError, httpx.RemoteProtocolError))


def _log_failure(exc: httpx.HTTPError, attempt: int, started: float, retry: bool) -> None:
    """실패를 숫자로 남긴다. 상류 장애와 우리 문제를 로그에서 가르려는 것이고, 질문과 응답 내용은 넣지 않는다."""
    status = exc.response.status_code if isinstance(exc, httpx.HTTPStatusError) else "-"
    logger.warning(
        "Gemini request failed: attempt=%d status=%s error=%s elapsed_ms=%d retry=%s",
        attempt,
        status,
        type(exc).__name__,
        (time.monotonic() - started) * 1000,
        str(retry).lower(),
    )


def _provider_error(exc: httpx.HTTPError) -> Exception:
    if isinstance(exc, httpx.TimeoutException):
        return ChatbotProviderTimeoutError("Gemini request timed out")
    if isinstance(exc, httpx.HTTPStatusError):
        return ChatbotProviderUpstreamError(f"Gemini request failed with status {exc.response.status_code}")
    return ChatbotProviderUpstreamError("Gemini request failed")


class GeminiChatbotProvider(ChatbotProvider):
    name = "gemini"

    def __init__(self):
        settings = ChatbotSettings.load()
        self.api_key = settings.gemini_api_key.strip()
        self.model = settings.gemini_model.strip() or ChatbotSettings.model_fields["gemini_model"].default
        self.timeout_seconds = settings.gemini_timeout_seconds

    async def generate_answer(self, prompt: str, json_output: bool = False, timeout: float | None = None) -> str:
        payload = {
            "contents": [
                {
                    "role": "user",
                    "parts": [{"text": prompt}],
                }
            ],
            "generationConfig": {
                "temperature": 0.4,
                "maxOutputTokens": 1024,
            },
        }
        if json_output:
            payload["generationConfig"]["responseMimeType"] = "application/json"

        response_body = await self._post(payload, timeout)
        answer = self._extract_answer(response_body)
        if not answer:
            raise ChatbotProviderUpstreamError("Gemini response did not include answer text")
        return answer

    async def _post(self, payload: dict, timeout: float | None) -> dict:
        if not self.api_key:
            raise ChatbotProviderConfigurationError("GEMINI_API_KEY is not configured")

        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent"
        timeout_seconds = self.timeout_seconds if timeout is None else max(0.1, min(self.timeout_seconds, timeout))
        # 남은 시간을 받은 호출(에이전트의 비상 경로)은 시간이 정해져 있어 다시 시도하지 않는다.
        attempts = 1 if timeout is not None else MAX_ATTEMPTS
        async with httpx.AsyncClient(timeout=timeout_seconds) as client:
            for attempt in range(1, attempts + 1):
                started = time.monotonic()
                try:
                    response = await client.post(
                        url,
                        headers={
                            "x-goog-api-key": self.api_key,
                            "Content-Type": "application/json",
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
            raise ChatbotProviderUpstreamError("Gemini response was not valid JSON") from exc

        self._log_response_metadata(response_body)
        self._raise_if_response_truncated(response_body)
        return response_body

    @staticmethod
    def _raise_if_response_truncated(response_body: dict) -> None:
        candidates = response_body.get("candidates") or []
        if not candidates:
            return

        finish_reason = candidates[0].get("finishReason")
        if finish_reason == "MAX_TOKENS":
            raise ChatbotProviderTruncatedError("Gemini response was truncated by token limit")

    @staticmethod
    def _log_response_metadata(response_body: dict) -> None:
        candidates = response_body.get("candidates") or []
        usage = response_body.get("usageMetadata") or {}
        finish_reason = candidates[0].get("finishReason") if candidates else None

        logger.info(
            "Gemini response metadata: finishReason=%s promptTokenCount=%s "
            "candidatesTokenCount=%s totalTokenCount=%s",
            finish_reason,
            usage.get("promptTokenCount"),
            usage.get("candidatesTokenCount"),
            usage.get("totalTokenCount"),
        )

    @staticmethod
    def _extract_answer(response_body: dict) -> str:
        candidates = response_body.get("candidates") or []
        if not candidates:
            return ""

        parts = candidates[0].get("content", {}).get("parts", [])
        texts = [
            part.get("text", "")
            for part in parts
            if isinstance(part, dict) and part.get("text")
        ]
        return "\n".join(text.strip() for text in texts if text.strip()).strip()
