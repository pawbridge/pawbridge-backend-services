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

    async def generate_turn(self, messages: list, tools: list, allow_tools: bool, timeout: float):
        """에이전트의 모델 호출 한 번. 최종 답은 늘 JSON으로 받는다(11/10 확인: 도구 선언과 JSON 모드를 같이 쓸 수 있음)."""
        # 에이전트 모드에서만 불러 고정 흐름의 기동 메모리를 늘리지 않는다.
        from langchain_core.messages import AIMessage

        payload = {
            "contents": _to_gemini_contents(messages),
            "generationConfig": {
                "temperature": 0.4,
                "maxOutputTokens": 1024,
                "responseMimeType": "application/json",
            },
        }
        if tools:
            payload["tools"] = [{"functionDeclarations": [_function_declaration(tool) for tool in tools]}]
            # 상한에 닿으면 선언은 두고 호출만 막는다. 대화에 이미 도구 호출이 있어 선언을 빼지 않는다.
            payload["toolConfig"] = {"functionCallingConfig": {"mode": "AUTO" if allow_tools else "NONE"}}

        response_body = await self._post(payload, timeout)
        candidates = response_body.get("candidates") or []
        parts = candidates[0].get("content", {}).get("parts", []) if candidates else []
        parts = [part for part in parts if isinstance(part, dict)]
        tool_calls = []
        for index, part in enumerate(parts):
            call = part.get("functionCall")
            if isinstance(call, dict) and call.get("name"):
                args = call.get("args") if isinstance(call.get("args"), dict) else {}
                tool_calls.append({"name": call["name"], "args": args, "id": call.get("id") or f"call-{index}"})
        return AIMessage(
            content=self._extract_answer(response_body),
            tool_calls=tool_calls,
            # Gemini 3은 도구 호출 조각에 thoughtSignature를 붙이고, 다음 호출에 그 조각을 그대로 돌려받아야 한다.
            additional_kwargs={"gemini_parts": parts},
        )

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


def _function_declaration(tool) -> dict:
    """도구 명세(이름, 설명, pydantic 입력 모델)를 Gemini 함수 선언으로 바꾼다. Gemini가 받는 칸만 남긴다."""
    schema = tool.args_schema.model_json_schema()
    properties = {}
    for name, field in schema.get("properties", {}).items():
        properties[name] = {key: field[key] for key in ("type", "description", "maxLength", "enum") if key in field}
    parameters = {"type": "object", "properties": properties, "required": schema.get("required", [])}
    return {"name": tool.name, "description": tool.description, "parameters": parameters}


def _to_gemini_contents(messages: list) -> list[dict]:
    """langchain_core 메시지를 Gemini contents로 바꾼다. 이어지는 도구 결과는 한 user 차례에 묶는다."""
    contents: list[dict] = []
    for message in messages:
        if message.type == "human":
            contents.append({"role": "user", "parts": [{"text": message.content}]})
        elif message.type == "ai":
            parts = message.additional_kwargs.get("gemini_parts")
            if not parts:
                parts = [{"text": message.content}] if message.content else []
                parts += [{"functionCall": {"name": call["name"], "args": call["args"]}} for call in message.tool_calls]
            contents.append({"role": "model", "parts": parts})
        elif message.type == "tool":
            part = {"functionResponse": {"name": message.name, "id": message.tool_call_id, "response": {"result": message.content}}}
            last = contents[-1] if contents else None
            if last and last["role"] == "user" and "functionResponse" in last["parts"][0]:
                last["parts"].append(part)
            else:
                contents.append({"role": "user", "parts": [part]})
        else:
            raise ValueError(f"Unsupported message type: {message.type}")
    return contents
