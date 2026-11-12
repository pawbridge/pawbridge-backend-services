import json
import os
import unittest
from unittest.mock import patch

import httpx

from app.chatbot.anthropic_provider import AnthropicChatbotProvider
from app.chatbot.provider import (
    ChatbotProviderConfigurationError,
    ChatbotProviderTimeoutError,
    ChatbotProviderTruncatedError,
    ChatbotProviderUpstreamError,
)


def finished(*texts, stop_reason="end_turn"):
    return {
        "type": "message",
        "content": [{"type": "text", "text": text} for text in texts],
        "stop_reason": stop_reason,
        "usage": {"input_tokens": 10, "output_tokens": 5},
    }


class AnthropicProviderTest(unittest.IsolatedAsyncioTestCase):
    async def send(self, body, json_output=False, environment=None, timeout=None):
        """응답 하나를 돌려주는 가짜 서버로 부르고, 답과 보낸 요청, 시간 제한을 돌려준다."""
        requests = []

        def respond(request):
            requests.append(request)
            return httpx.Response(200, json=body)

        client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        with (
            patch.dict(os.environ, {"ANTHROPIC_API_KEY": "test-only-key"} if environment is None else environment, clear=True),
            patch("app.chatbot.anthropic_provider.httpx.AsyncClient", return_value=client) as factory,
        ):
            try:
                answer = await AnthropicChatbotProvider().generate_answer("질문", json_output=json_output, timeout=timeout)
            except Exception as exc:  # noqa: BLE001 - 시험에서 오류 종류를 그대로 비교한다
                answer = exc
        return answer, requests, factory.call_args.kwargs["timeout"] if factory.call_args else None

    def test_missing_or_blank_model_uses_default(self):
        for environment in ({}, {"ANTHROPIC_MODEL": "  "}):
            with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True):
                self.assertEqual(AnthropicChatbotProvider().model, "claude-haiku-5-5")

    async def test_request_turns_thinking_off_without_temperature(self):
        answer, requests, _ = await self.send(finished("답"))
        self.assertEqual(answer, "답")
        self.assertEqual(len(requests), 1)
        request = requests[0]
        self.assertEqual(str(request.url), "https://api.anthropic.com/v1/messages")
        self.assertEqual(request.headers["x-api-key"], "test-only-key")
        self.assertEqual(request.headers["anthropic-version"], "2023-06-01")
        self.assertEqual(json.loads(request.content), {
            "model": "claude-haiku-5-5",
            "max_tokens": 1024,
            "messages": [{"role": "user", "content": "질문"}],
            "thinking": {"type": "disabled"},
        })

    async def test_json_output_sends_answer_schema(self):
        _, requests, _ = await self.send(finished('{"answer": "답", "used": []}'), json_output=True)
        output_format = json.loads(requests[0].content)["output_config"]["format"]
        self.assertEqual(output_format["type"], "json_schema")
        self.assertEqual(output_format["schema"]["required"], ["answer", "used"])

    async def test_text_blocks_are_joined_and_other_blocks_skipped(self):
        body = finished("첫 ", "둘")
        body["content"].insert(0, {"type": "thinking", "thinking": "", "signature": "x"})
        answer, _, _ = await self.send(body)
        self.assertEqual(answer, "첫 둘")

    async def test_token_limit_is_a_truncated_error(self):
        answer, _, _ = await self.send(finished("잘", stop_reason="max_tokens"))
        self.assertIsInstance(answer, ChatbotProviderTruncatedError)

    async def test_refusal_or_empty_answer_is_upstream_error(self):
        for body in (finished("일부", stop_reason="refusal"), finished()):
            with self.subTest(body=body):
                answer, _, _ = await self.send(body)
                self.assertIsInstance(answer, ChatbotProviderUpstreamError)
                self.assertNotIsInstance(answer, ChatbotProviderTruncatedError)

    async def test_missing_key_is_a_configuration_error(self):
        answer, requests, _ = await self.send(finished("답"), environment={})
        self.assertIsInstance(answer, ChatbotProviderConfigurationError)
        self.assertEqual(requests, [])

    async def test_timeout_is_capped_by_remaining_time(self):
        for given, expected in ((4.2, 4.2), (99, 15), (None, 15)):
            with self.subTest(given=given):
                _, _, timeout = await self.send(finished("답"), timeout=given)
                self.assertEqual(timeout, expected)


class AnthropicRetryTest(unittest.IsolatedAsyncioTestCase):
    """일시 오류(5xx, 과부하 529, 연결 실패)만 한 번 다시 시도하고, 실패는 숫자로만 로그에 남긴다."""

    async def run_with(self, outcomes, timeout=None):
        calls = []

        def respond(request):
            calls.append(request)
            outcome = outcomes[min(len(calls), len(outcomes)) - 1]
            if isinstance(outcome, Exception):
                raise outcome
            body = finished("답") if outcome == 200 else {"type": "error", "error": {"message": "x"}}
            return httpx.Response(outcome, json=body)

        client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        with (
            patch.dict(os.environ, {"ANTHROPIC_API_KEY": "test-only-key"}, clear=True),
            patch("app.chatbot.anthropic_provider.httpx.AsyncClient", return_value=client),
            patch("app.chatbot.anthropic_provider.asyncio.sleep") as sleep,
            self.assertLogs("uvicorn.error", level="INFO") as logs,
        ):
            try:
                result = await AnthropicChatbotProvider().generate_answer("질문 원문", timeout=timeout)
            except Exception as exc:  # noqa: BLE001 - 시험에서 오류 종류를 그대로 비교한다
                result = exc
        failures = [line for line in logs.output if "Anthropic request failed" in line]
        return result, len(calls), failures, sleep

    async def test_overload_is_retried_once_and_succeeds(self):
        result, calls, failures, sleep = await self.run_with([529, 200])
        self.assertEqual((result, calls), ("답", 2))
        sleep.assert_awaited_once()
        self.assertIn("attempt=1 status=529 error=HTTPStatusError", failures[0])
        self.assertIn("retry=true", failures[0])

    async def test_second_server_error_gives_up_as_upstream_error(self):
        result, calls, _, _ = await self.run_with([503, 503, 200])
        self.assertIsInstance(result, ChatbotProviderUpstreamError)
        self.assertEqual(calls, 2)

    async def test_connection_failure_is_retried_once(self):
        result, calls, failures, _ = await self.run_with([httpx.ConnectError("down"), 200])
        self.assertEqual((result, calls), ("답", 2))
        self.assertIn("status=- error=ConnectError", failures[0])

    async def test_rate_limit_timeout_and_bad_request_are_not_retried(self):
        for outcome, expected in ((429, ChatbotProviderUpstreamError), (400, ChatbotProviderUpstreamError),
                                  (httpx.ReadTimeout("slow"), ChatbotProviderTimeoutError)):
            with self.subTest(outcome=outcome):
                result, calls, failures, sleep = await self.run_with([outcome, 200])
                self.assertIsInstance(result, expected)
                self.assertEqual(calls, 1)
                sleep.assert_not_awaited()
                self.assertIn("retry=false", failures[0])

    async def test_time_budgeted_call_is_not_retried(self):
        _, calls, _, sleep = await self.run_with([503, 200], timeout=6)
        self.assertEqual(calls, 1)
        sleep.assert_not_awaited()

    async def test_failure_log_has_no_prompt_or_key(self):
        _, _, failures, _ = await self.run_with([503, 503])
        self.assertTrue(failures)
        self.assertTrue(all("질문 원문" not in line and "test-only-key" not in line for line in failures))


if __name__ == "__main__":
    unittest.main()
