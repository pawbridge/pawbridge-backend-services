import logging

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
        try:
            async with httpx.AsyncClient(timeout=timeout_seconds) as client:
                response = await client.post(
                    url,
                    headers={
                        "x-goog-api-key": self.api_key,
                        "Content-Type": "application/json",
                    },
                    json=payload,
                )
                response.raise_for_status()
        except httpx.TimeoutException as exc:
            raise ChatbotProviderTimeoutError("Gemini request timed out") from exc
        except httpx.HTTPStatusError as exc:
            raise ChatbotProviderUpstreamError(
                f"Gemini request failed with status {exc.response.status_code}"
            ) from exc
        except httpx.HTTPError as exc:
            raise ChatbotProviderUpstreamError("Gemini request failed") from exc

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
