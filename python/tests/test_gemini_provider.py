import json
import os
import unittest
from unittest.mock import patch

import httpx

from app.chatbot.gemini_provider import GeminiChatbotProvider


class GeminiProviderTest(unittest.IsolatedAsyncioTestCase):
    def test_missing_or_blank_model_uses_stable_default(self):
        for environment in ({}, {"GEMINI_MODEL": "  "}):
            with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True):
                self.assertEqual(GeminiChatbotProvider().model, "gemini-3.5-flash-lite")

    async def test_request_uses_selected_model_without_legacy_thinking_budget(self):
        for override in (None, " custom-model "):
            environment = {"GEMINI_API_KEY": "test-only-key"}
            if override is not None:
                environment["GEMINI_MODEL"] = override
            expected_model = override.strip() if override else "gemini-3.5-flash-lite"
            requests = []

            def respond(request):
                requests.append(request)
                return httpx.Response(200, json={
                    "candidates": [{
                        "finishReason": "STOP",
                        "content": {"parts": [{"text": "test answer"}]},
                    }],
                })

            client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
            with (
                self.subTest(model=expected_model),
                patch.dict(os.environ, environment, clear=True),
                patch("app.chatbot.gemini_provider.httpx.AsyncClient", return_value=client),
            ):
                answer = await GeminiChatbotProvider().generate_answer("test prompt")

                self.assertEqual(answer, "test answer")
                self.assertEqual(len(requests), 1)
                request = requests[0]
                self.assertEqual(request.method, "POST")
                self.assertEqual(str(request.url),
                    f"https://generativelanguage.googleapis.com/v1beta/models/{expected_model}:generateContent")
                self.assertEqual(request.headers["x-goog-api-key"], "test-only-key")
                payload = json.loads(request.content)
                self.assertEqual(payload["contents"], [
                    {"role": "user", "parts": [{"text": "test prompt"}]},
                ])
                self.assertEqual(payload["generationConfig"], {
                    "temperature": 0.4,
                    "maxOutputTokens": 1024,
                })


class GeminiTimeoutTest(unittest.IsolatedAsyncioTestCase):
    async def answer_with_timeout(self, timeout):
        def respond(request):
            return httpx.Response(200, json={"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": "답"}]}}]})

        client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        with (
            patch.dict(os.environ, {"GEMINI_API_KEY": "test-only-key"}, clear=True),
            patch("app.chatbot.gemini_provider.httpx.AsyncClient", return_value=client) as factory,
        ):
            await GeminiChatbotProvider().generate_answer("질문", timeout=timeout)
        return factory.call_args.kwargs["timeout"]

    async def test_timeout_is_capped_by_remaining_time(self):
        # 에이전트가 고정 흐름으로 넘어갈 때 남은 시간만 쓴다. 기본 시간 제한(15초)보다 길게 늘리지는 않는다.
        self.assertEqual(await self.answer_with_timeout(4.2), 4.2)
        self.assertEqual(await self.answer_with_timeout(99), 15)
        self.assertEqual(await self.answer_with_timeout(None), 15)

    async def test_timeout_is_its_own_error(self):
        from app.chatbot.provider import ChatbotProviderTimeoutError

        def respond(request):
            raise httpx.ReadTimeout("slow", request=request)

        client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        with (
            patch.dict(os.environ, {"GEMINI_API_KEY": "test-only-key"}, clear=True),
            patch("app.chatbot.gemini_provider.httpx.AsyncClient", return_value=client),
        ):
            with self.assertRaises(ChatbotProviderTimeoutError):
                await GeminiChatbotProvider().generate_answer("질문")


class GeminiRetryTest(unittest.IsolatedAsyncioTestCase):
    """일시 오류(5xx, 연결 실패)만 한 번 다시 시도하고, 실패는 숫자로만 로그에 남긴다."""

    OK = {"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": "답"}]}}]}

    async def run_with(self, outcomes, timeout=None):
        calls = []

        def respond(request):
            calls.append(request)
            outcome = outcomes[min(len(calls), len(outcomes)) - 1]
            if isinstance(outcome, Exception):
                raise outcome
            return httpx.Response(outcome, json=self.OK if outcome == 200 else {"error": {"code": outcome}})

        client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        with (
            patch.dict(os.environ, {"GEMINI_API_KEY": "test-only-key"}, clear=True),
            patch("app.chatbot.gemini_provider.httpx.AsyncClient", return_value=client),
            patch("app.chatbot.gemini_provider.asyncio.sleep") as sleep,
            self.assertLogs("uvicorn.error", level="INFO") as logs,
        ):
            try:
                result = await GeminiChatbotProvider().generate_answer("질문 원문", timeout=timeout)
            except Exception as exc:  # noqa: BLE001 - 시험에서 오류 종류를 그대로 비교한다
                result = exc
        failures = [line for line in logs.output if "Gemini request failed" in line]
        return result, len(calls), failures, sleep

    async def test_server_error_is_retried_once_and_succeeds(self):
        result, calls, failures, sleep = await self.run_with([503, 200])
        self.assertEqual(result, "답")
        self.assertEqual(calls, 2)
        sleep.assert_awaited_once()
        self.assertEqual(len(failures), 1)
        self.assertIn("attempt=1 status=503 error=HTTPStatusError", failures[0])
        self.assertIn("retry=true", failures[0])

    async def test_second_server_error_gives_up_as_upstream_error(self):
        from app.chatbot.provider import ChatbotProviderUpstreamError

        result, calls, failures, _ = await self.run_with([503, 503, 200])
        self.assertIsInstance(result, ChatbotProviderUpstreamError)
        self.assertEqual(calls, 2)
        self.assertEqual([("retry=true" in line, "attempt=2" in line) for line in failures], [(True, False), (False, True)])

    async def test_connection_failure_is_retried_once(self):
        result, calls, failures, _ = await self.run_with([httpx.ConnectError("down"), 200])
        self.assertEqual((result, calls), ("답", 2))
        self.assertIn("status=- error=ConnectError", failures[0])

    async def test_rate_limit_timeout_and_bad_request_are_not_retried(self):
        from app.chatbot.provider import ChatbotProviderTimeoutError, ChatbotProviderUpstreamError

        for outcome, expected in ((429, ChatbotProviderUpstreamError), (400, ChatbotProviderUpstreamError),
                                  (httpx.ReadTimeout("slow"), ChatbotProviderTimeoutError)):
            with self.subTest(outcome=outcome):
                result, calls, failures, sleep = await self.run_with([outcome, 200])
                self.assertIsInstance(result, expected)
                self.assertEqual(calls, 1)
                sleep.assert_not_awaited()
                self.assertIn("retry=false", failures[0])

    async def test_time_budgeted_call_is_not_retried(self):
        # 에이전트가 남은 시간을 넘긴 비상 경로 호출은 다시 시도하지 않는다.
        _, calls, _, sleep = await self.run_with([503, 200], timeout=6)
        self.assertEqual(calls, 1)
        sleep.assert_not_awaited()

    async def test_failure_log_has_no_prompt_or_key(self):
        _, _, failures, _ = await self.run_with([503, 503])
        self.assertTrue(failures)
        self.assertTrue(all("질문 원문" not in line and "test-only-key" not in line for line in failures))
