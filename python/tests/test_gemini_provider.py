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


class GeminiToolTurnTest(unittest.IsolatedAsyncioTestCase):
    async def turn(self, response_body, messages=None, allow_tools=True, timeout=10.0):
        from langchain_core.messages import HumanMessage

        from app.chatbot.agent.tools import SEARCH_HEALTH

        requests = []

        def respond(request):
            requests.append(request)
            return httpx.Response(200, json=response_body)

        client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        with (
            patch.dict(os.environ, {"GEMINI_API_KEY": "test-only-key"}, clear=True),
            patch("app.chatbot.gemini_provider.httpx.AsyncClient", return_value=client) as factory,
        ):
            reply = await GeminiChatbotProvider().generate_turn(
                messages or [HumanMessage(content="질문")], [SEARCH_HEALTH], allow_tools, timeout)
        return reply, json.loads(requests[0].content), factory.call_args.kwargs["timeout"]

    async def test_function_call_becomes_tool_call_and_keeps_raw_parts(self):
        part = {"functionCall": {"name": "search_health", "args": {"query": "기침"}, "id": "call-9"}, "thoughtSignature": "sig"}
        reply, payload, _ = await self.turn({"candidates": [{"finishReason": "STOP", "content": {"parts": [part]}}]})
        self.assertEqual(reply.tool_calls[0]["name"], "search_health")
        self.assertEqual(reply.tool_calls[0]["args"], {"query": "기침"})
        self.assertEqual(reply.tool_calls[0]["id"], "call-9")
        self.assertEqual(reply.additional_kwargs["gemini_parts"], [part])
        declaration = payload["tools"][0]["functionDeclarations"][0]
        self.assertEqual(declaration["name"], "search_health")
        self.assertEqual(declaration["parameters"]["required"], ["query"])
        self.assertEqual(declaration["parameters"]["properties"]["query"]["type"], "string")
        self.assertEqual(payload["toolConfig"], {"functionCallingConfig": {"mode": "AUTO"}})
        self.assertEqual(payload["generationConfig"]["responseMimeType"], "application/json")

    async def test_blocked_tools_keep_declarations_and_set_mode_none(self):
        body = {"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": '{"answer": "답", "used": []}'}]}}]}
        reply, payload, _ = await self.turn(body, allow_tools=False)
        self.assertEqual(reply.tool_calls, [])
        self.assertEqual(reply.content, '{"answer": "답", "used": []}')
        self.assertIn("tools", payload)
        self.assertEqual(payload["toolConfig"], {"functionCallingConfig": {"mode": "NONE"}})

    async def test_history_returns_signed_parts_and_groups_tool_results(self):
        from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

        signed = [
            {"functionCall": {"name": "search_health", "args": {"query": "기침"}, "id": "a"}, "thoughtSignature": "sig"},
            {"functionCall": {"name": "search_health", "args": {"query": "콧물"}, "id": "b"}},
        ]
        history = [
            HumanMessage(content="질문"),
            AIMessage(content="", tool_calls=[{"name": "search_health", "args": {"query": "기침"}, "id": "a"},
                                              {"name": "search_health", "args": {"query": "콧물"}, "id": "b"}],
                      additional_kwargs={"gemini_parts": signed}),
            ToolMessage(content="[1] 결과", tool_call_id="a", name="search_health"),
            ToolMessage(content="없음", tool_call_id="b", name="search_health"),
        ]
        body = {"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": "{}"}]}}]}
        _, payload, _ = await self.turn(body, messages=history)
        contents = payload["contents"]
        self.assertEqual([content["role"] for content in contents], ["user", "model", "user"])
        self.assertEqual(contents[1]["parts"], signed)
        self.assertEqual(
            [part["functionResponse"] for part in contents[2]["parts"]],
            [{"name": "search_health", "id": "a", "response": {"result": "[1] 결과"}},
             {"name": "search_health", "id": "b", "response": {"result": "없음"}}],
        )

    async def test_timeout_is_capped_by_remaining_time(self):
        body = {"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": "{}"}]}}]}
        _, _, timeout = await self.turn(body, timeout=4.2)
        self.assertEqual(timeout, 4.2)
        _, _, timeout = await self.turn(body, timeout=99)
        self.assertEqual(timeout, 15)

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
