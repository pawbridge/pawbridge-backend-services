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
