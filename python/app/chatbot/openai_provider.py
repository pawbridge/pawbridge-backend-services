"""고정 흐름(CHATBOT_MODE=rag, 에이전트의 비상 경로)에서 OpenAI를 한 번 부른다.

gemini_provider.py와 같은 규칙을 따른다: 일시 오류(5xx, 연결 실패)만 한 번 더 보내고,
429(요청 한도), 시간 초과, 400(요청 잘못)은 바로 실패한다. 질문과 답은 OpenAI 쪽에 저장하지 않는다(store=false).
"""

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

RESPONSES_URL = "https://api.openai.com/v1/responses"
MAX_ATTEMPTS = 2
RETRY_DELAY_SECONDS = 1.0
# 고정 흐름은 모델을 한 번 불러 바로 답하므로 생각 단계를 쓰지 않는다(Gemini 고정 흐름과 같은 출력 상한).
MAX_OUTPUT_TOKENS = 1024


def _is_transient(exc: httpx.HTTPError) -> bool:
    if isinstance(exc, httpx.HTTPStatusError):
        return exc.response.status_code >= 500
    return isinstance(exc, (httpx.ConnectError, httpx.RemoteProtocolError))


def _log_failure(exc: httpx.HTTPError, attempt: int, started: float, retry: bool) -> None:
    """실패를 숫자로 남긴다. 질문과 응답 내용, 키는 넣지 않는다."""
    status = exc.response.status_code if isinstance(exc, httpx.HTTPStatusError) else "-"
    logger.warning(
        "OpenAI request failed: attempt=%d status=%s error=%s elapsed_ms=%d retry=%s",
        attempt,
        status,
        type(exc).__name__,
        (time.monotonic() - started) * 1000,
        str(retry).lower(),
    )


def _provider_error(exc: httpx.HTTPError) -> Exception:
    if isinstance(exc, httpx.TimeoutException):
        return ChatbotProviderTimeoutError("OpenAI request timed out")
    if isinstance(exc, httpx.HTTPStatusError):
        return ChatbotProviderUpstreamError(f"OpenAI request failed with status {exc.response.status_code}")
    return ChatbotProviderUpstreamError("OpenAI request failed")


class OpenAIChatbotProvider(ChatbotProvider):
    name = "openai"

    def __init__(self):
        settings = ChatbotSettings.load()
        self.api_key = settings.openai_api_key.strip()
        self.model = settings.openai_model.strip() or ChatbotSettings.model_fields["openai_model"].default
        self.timeout_seconds = settings.openai_timeout_seconds

    async def generate_answer(self, prompt: str, json_output: bool = False, timeout: float | None = None) -> str:
        payload = {
            "model": self.model,
            "input": prompt,
            "reasoning": {"effort": "none"},
            "max_output_tokens": MAX_OUTPUT_TOKENS,
            "store": False,
        }
        if json_output:
            payload["text"] = {"format": {"type": "json_object"}}

        response_body = await self._post(payload, timeout)
        answer = self._extract_answer(response_body)
        if not answer:
            raise ChatbotProviderUpstreamError("OpenAI response did not include answer text")
        return answer

    async def _post(self, payload: dict, timeout: float | None) -> dict:
        if not self.api_key:
            raise ChatbotProviderConfigurationError("OPENAI_API_KEY is not configured")

        timeout_seconds = self.timeout_seconds if timeout is None else max(0.1, min(self.timeout_seconds, timeout))
        # 남은 시간을 받은 호출(에이전트의 비상 경로)은 시간이 정해져 있어 다시 시도하지 않는다.
        attempts = 1 if timeout is not None else MAX_ATTEMPTS
        async with httpx.AsyncClient(timeout=timeout_seconds) as client:
            for attempt in range(1, attempts + 1):
                started = time.monotonic()
                try:
                    response = await client.post(
                        RESPONSES_URL,
                        headers={
                            "Authorization": f"Bearer {self.api_key}",
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
            raise ChatbotProviderUpstreamError("OpenAI response was not valid JSON") from exc

        self._log_response_metadata(response_body)
        self._raise_if_response_truncated(response_body)
        return response_body

    @staticmethod
    def _raise_if_response_truncated(response_body: dict) -> None:
        if response_body.get("status") != "incomplete":
            return
        reason = (response_body.get("incomplete_details") or {}).get("reason")
        if reason == "max_output_tokens":
            raise ChatbotProviderTruncatedError("OpenAI response was truncated by token limit")
        raise ChatbotProviderUpstreamError(f"OpenAI response was incomplete ({reason})")

    @staticmethod
    def _log_response_metadata(response_body: dict) -> None:
        usage = response_body.get("usage") or {}
        logger.info(
            "OpenAI response metadata: status=%s inputTokens=%s outputTokens=%s totalTokens=%s",
            response_body.get("status"),
            usage.get("input_tokens"),
            usage.get("output_tokens"),
            usage.get("total_tokens"),
        )

    @staticmethod
    def _extract_answer(response_body: dict) -> str:
        """답 메시지의 글을 꺼낸다. 최종 답 앞에 중간 설명 메시지(phase=commentary)가 올 수 있어
        phase가 final_answer인 메시지가 있으면 그것만 쓴다(11/11 시험 호출에서 메시지 3개로 나뉜 적이 있음)."""
        messages = [
            item for item in response_body.get("output") or []
            if isinstance(item, dict) and item.get("type") == "message"
        ]
        finals = [item for item in messages if item.get("phase") == "final_answer"]
        texts = []
        for item in finals or messages:
            for part in item.get("content") or []:
                if isinstance(part, dict) and part.get("type") == "output_text" and part.get("text"):
                    texts.append(part["text"])
        return "".join(texts).strip()
