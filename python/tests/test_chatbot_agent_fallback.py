"""주 제공자가 일시 장애일 때 예비 제공자(LLM_FALLBACK_PROVIDER)로 질문을 처음부터 다시 푸는지 본다."""

import os
import unittest
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient
from langchain_core.exceptions import (
    ModelAPIError,
    ModelAuthenticationError,
    ModelInvalidRequestError,
    ModelRateLimitError,
)
from langchain_core.messages import AIMessage, HumanMessage

from app.chatbot.agent import models
from app.chatbot.agent.breaker import ProviderBreaker, primary_breaker
from app.chatbot.provider import ChatbotProviderConfigurationError
from app.entrypoints.chatbot_api import app
from app.settings import ChatbotSettings
import tests.test_chatbot_agent as base
from tests.test_chatbot_agent import AGENT_ENV, FixedProvider, ScriptedChatModel, call, reply

# 주 제공자는 AGENT_ENV 기본값인 stub, 예비는 openai다. 실제 모델 대신 가짜 모델을 끼운다.
FALLBACK_ENV = {"LLM_FALLBACK_PROVIDER": "openai"}


class BackupProvider(FixedProvider):
    name = "backup"


class FallbackProviderTest(unittest.TestCase):
    # 기존 에이전트 시험의 준비(가짜 해설 검색, 고정 흐름 제공자)와 요청 보내기를 그대로 쓴다.
    # 클래스를 이름으로 가져오면 이 파일에서 기존 시험이 한 번 더 돌므로 모듈로 가져온다.
    post = base.AgentServiceTest.post

    def setUp(self):
        base.AgentServiceTest.setUp(self)
        self.backup = BackupProvider("예비 고정 흐름 답입니다.")
        # 차단기는 프로세스에 하나라 시험마다 닫힌 상태로 시작한다.
        primary_breaker().reset()
        self.addCleanup(primary_breaker().reset)

    def post_with_backup(self, primary, backup_model, env=None, deadline=None):
        patches = [
            patch("app.chatbot.agent.service.fallback_chat_model", return_value=backup_model),
            patch("app.chatbot.agent.service.get_chatbot_provider", return_value=self.backup),
        ]
        if deadline is not None:
            patches.append(patch("app.chatbot.agent.service.DEADLINE_SECONDS", deadline))
        for patcher in patches:
            patcher.start()
        try:
            with self.assertLogs("uvicorn.error", level="INFO") as logs:
                response = self.post(primary, {**FALLBACK_ENV, **(env or {})})
        finally:
            for patcher in reversed(patches):
                patcher.stop()
        return response, [message for message in logs.output if "Chatbot agent:" in message]

    def test_overloaded_primary_is_answered_by_backup_from_the_start(self):
        primary = ScriptedChatModel(turns=[ModelAPIError("529 overloaded")])
        backup = ScriptedChatModel(turns=[reply("보호소에 물어보세요.")])
        response, lines = self.post_with_backup(primary, backup)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["answer"], "보호소에 물어보세요.")
        # 응답의 provider는 실제로 답한 쪽이다(백엔드가 메시지와 함께 저장한다).
        self.assertEqual(response.json()["provider"], "backup")
        self.assertEqual(len(primary.calls), 1)
        self.assertEqual(len(backup.calls), 1)
        self.assertEqual(len(lines), 2)
        self.assertIn("path=switch stop=upstream error=ModelAPIError model_calls=1", lines[0])
        self.assertTrue(lines[0].endswith("provider=stub"))
        self.assertIn("path=agent stop=- error=- model_calls=1", lines[1])
        self.assertTrue(lines[1].endswith("provider=openai"))

    def test_backup_starts_over_without_primary_messages(self):
        # 주 제공자가 도구를 한 번 부른 뒤 두 번째 호출에서 한도에 걸린다.
        primary = ScriptedChatModel(turns=[call("기침"), ModelRateLimitError("429")])
        backup = ScriptedChatModel(turns=[call("기침"), reply("기침이 계속되면 동물병원 진료가 필요합니다 [2].", [2])])
        response, lines = self.post_with_backup(primary, backup)
        self.assertEqual(response.status_code, 200)
        first = backup.calls[0]["messages"]
        self.assertEqual(len(first), 1)
        self.assertIsInstance(first[0], HumanMessage)
        # 시도마다 새로 세므로 첫 호출 도구 강제도 예비 모델에 다시 걸린다(첫 검색이 근거를 못 찾은 질문).
        self.assertEqual(primary.calls[0]["tool_choice"], "any")
        self.assertEqual(backup.calls[0]["tool_choice"], "any")
        self.assertIn("path=agent stop=- error=- model_calls=2 tool_calls=1", lines[1])
        self.assertEqual(len(response.json()["sources"]), 1)

    def test_timeout_with_time_left_switches_to_backup(self):
        primary = ScriptedChatModel(turns=[httpx.ReadTimeout("slow")])
        backup = ScriptedChatModel(turns=[reply("보호소에 물어보세요.")])
        response, lines = self.post_with_backup(primary, backup)
        self.assertEqual(response.json()["answer"], "보호소에 물어보세요.")
        self.assertIn("path=switch stop=deadline error=ReadTimeout", lines[0])

    def test_connection_failure_switches_to_backup(self):
        primary = ScriptedChatModel(turns=[httpx.ConnectError("down")])
        backup = ScriptedChatModel(turns=[reply("보호소에 물어보세요.")])
        response, _ = self.post_with_backup(primary, backup)
        self.assertEqual(response.status_code, 200)

    def test_used_up_credit_switches_to_backup(self):
        import anthropic

        request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
        body = {"type": "error", "error": {"type": "billing_error", "message": "credit"}}
        # LangChain은 402를 따로 감싸지 않아 SDK 오류 그대로 올라온다.
        error = anthropic.APIStatusError("402", response=httpx.Response(402, request=request), body=body)
        primary = ScriptedChatModel(turns=[error])
        backup = ScriptedChatModel(turns=[reply("보호소에 물어보세요.")])
        response, lines = self.post_with_backup(primary, backup)
        self.assertEqual(response.status_code, 200)
        self.assertIn("path=switch stop=upstream error=APIStatusError", lines[0])

    def test_errors_to_fix_are_not_hidden_by_backup(self):
        for error in (ModelAuthenticationError("401"), ModelInvalidRequestError("400")):
            with self.subTest(error=type(error).__name__):
                primary = ScriptedChatModel(turns=[error])
                backup = ScriptedChatModel(turns=[reply("불리면 안 됨")])
                response, lines = self.post_with_backup(primary, backup)
                self.assertEqual(response.status_code, 502)
                self.assertEqual(backup.calls, [])
                self.assertEqual(len(lines), 1)
                self.assertIn(f"path=error stop=upstream error={type(error).__name__}", lines[0])

    def test_little_time_left_does_not_switch(self):
        primary = ScriptedChatModel(turns=[ModelAPIError("503")])
        backup = ScriptedChatModel(turns=[reply("불리면 안 됨")])
        response, lines = self.post_with_backup(primary, backup, deadline=5.0)
        self.assertEqual(response.status_code, 502)
        self.assertEqual(backup.calls, [])
        self.assertTrue(lines[0].endswith("provider=stub"))

    def test_switches_only_once(self):
        primary = ScriptedChatModel(turns=[ModelAPIError("503")])
        backup = ScriptedChatModel(turns=[ModelAPIError("503")])
        response, lines = self.post_with_backup(primary, backup)
        self.assertEqual(response.status_code, 502)
        self.assertEqual(len(backup.calls), 1)
        self.assertEqual(len(lines), 2)
        self.assertIn("path=error stop=upstream error=ModelAPIError", lines[1])
        self.assertTrue(lines[1].endswith("provider=openai"))

    def test_fixed_flow_after_switch_uses_backup_provider(self):
        primary = ScriptedChatModel(turns=[ModelAPIError("503")])
        backup = ScriptedChatModel(turns=[AIMessage(content="  ")])
        response, lines = self.post_with_backup(primary, backup)
        self.assertEqual(response.json()["answer"], "예비 고정 흐름 답입니다.")
        self.assertEqual(self.fixed.calls, [])
        self.assertEqual(len(self.backup.calls), 1)
        self.assertEqual(response.json()["provider"], "backup")
        self.assertIn("path=fixed stop=empty_answer", lines[1])

    def test_breaker_opens_after_three_outages_and_skips_primary(self):
        for _ in range(3):
            primary = ScriptedChatModel(turns=[ModelAPIError("529 overloaded")])
            self.post_with_backup(primary, ScriptedChatModel(turns=[reply("보호소에 물어보세요.")]))
        self.assertEqual(primary_breaker().state, "open")
        primary = ScriptedChatModel(turns=[reply("불리면 안 됨")])
        backup = ScriptedChatModel(turns=[reply("보호소에 물어보세요.")])
        response, lines = self.post_with_backup(primary, backup)
        self.assertEqual(response.json()["answer"], "보호소에 물어보세요.")
        self.assertEqual(primary.calls, [])
        self.assertIn("path=skip stop=breaker_open error=- model_calls=0", lines[0])
        self.assertTrue(lines[0].endswith("provider=stub"))
        self.assertTrue(lines[1].endswith("provider=openai"))

    def test_errors_to_fix_do_not_open_breaker(self):
        for _ in range(3):
            self.post_with_backup(ScriptedChatModel(turns=[ModelAuthenticationError("401")]), ScriptedChatModel())
        self.assertEqual(primary_breaker().state, "closed")

    def test_our_own_bug_still_ends_a_probe(self):
        breaker = primary_breaker()
        for _ in range(3):
            breaker.record(unavailable=True)
        breaker.open_until = 0.0  # 열림 시간이 지났다고 본다
        with self.assertRaises(ValueError):
            self.post_with_backup(ScriptedChatModel(turns=[ValueError("bug in our code")]), ScriptedChatModel())
        self.assertEqual(breaker.state, "closed")

    def test_without_fallback_setting_breaker_is_not_used(self):
        for _ in range(4):
            primary = ScriptedChatModel(turns=[ModelAPIError("503")])
            self.assertEqual(self.post(primary).status_code, 502)
            self.assertEqual(len(primary.calls), 1)
        self.assertEqual(primary_breaker().state, "closed")

    def test_without_fallback_setting_upstream_error_is_502_as_before(self):
        primary = ScriptedChatModel(turns=[ModelAPIError("503")])
        with patch("app.chatbot.agent.service.fallback_chat_model") as fallback:
            response = self.post(primary)
        self.assertEqual(response.status_code, 502)
        fallback.assert_not_called()


class FakeClock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


class ProviderBreakerTest(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.breaker = ProviderBreaker(threshold=3, window_seconds=60, open_seconds=60, clock=self.clock)

    def fail(self, times=1, every=0.0):
        for _ in range(times):
            self.assertTrue(self.breaker.allow())
            self.breaker.record(unavailable=True)
            self.clock.now += every

    def test_opens_after_three_failures_within_a_minute(self):
        self.fail(2, every=10)
        self.assertEqual(self.breaker.state, "closed")
        self.fail(1)
        self.assertEqual(self.breaker.state, "open")
        self.assertFalse(self.breaker.allow())

    def test_failures_spread_over_more_than_a_minute_do_not_open(self):
        self.fail(5, every=31)
        self.assertEqual(self.breaker.state, "closed")

    def test_success_clears_failures(self):
        self.fail(2)
        self.breaker.record(unavailable=False)
        self.fail(2)
        self.assertEqual(self.breaker.state, "closed")

    def test_after_open_time_one_question_probes_and_others_still_skip(self):
        self.fail(3)
        self.clock.now += 59
        self.assertFalse(self.breaker.allow())
        self.clock.now += 2
        self.assertTrue(self.breaker.allow())
        self.assertEqual(self.breaker.state, "probing")
        self.assertFalse(self.breaker.allow())

    def test_probe_success_closes_and_probe_failure_reopens(self):
        self.fail(3)
        self.clock.now += 61
        self.assertTrue(self.breaker.allow())
        self.breaker.record(unavailable=False)
        self.assertEqual(self.breaker.state, "closed")

        self.fail(3)
        self.clock.now += 61
        self.assertTrue(self.breaker.allow())
        self.breaker.record(unavailable=True)
        self.assertEqual(self.breaker.state, "open")
        self.clock.now += 30
        self.assertFalse(self.breaker.allow())
        self.clock.now += 31
        self.assertTrue(self.breaker.allow())


class ProviderUnavailableTest(unittest.TestCase):
    def test_outage_and_used_up_credit_mean_unavailable(self):
        import anthropic
        import openai

        request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
        transient = [
            ModelRateLimitError("429"),
            ModelAPIError("5xx"),
            httpx.ConnectError("down"),
            httpx.ReadTimeout("slow"),
            TimeoutError(),
            anthropic.RateLimitError("429", response=httpx.Response(429, request=request), body=None),
            anthropic.InternalServerError("529", response=httpx.Response(529, request=request), body=None),
            anthropic.APIConnectionError(request=request),
            anthropic.APITimeoutError(request=request),
            openai.InternalServerError("503", response=httpx.Response(503, request=request), body=None),
            openai.APIConnectionError(request=request),
            # OpenAI의 크레딧 소진은 429 insufficient_quota다.
            openai.RateLimitError("429", response=httpx.Response(429, request=request),
                                  body={"type": "insufficient_quota", "code": "insufficient_quota"}),
            # Anthropic의 크레딧 소진. 지금 모양(402 billing_error)과 예전 모양(400 "credit balance is too low").
            anthropic.APIStatusError("402", response=httpx.Response(402, request=request),
                                     body={"type": "error", "error": {"type": "billing_error", "message": "x"}}),
            anthropic.BadRequestError("Your credit balance is too low to access the Anthropic API.",
                                      response=httpx.Response(400, request=request), body=None),
        ]
        for error in transient:
            with self.subTest(error=type(error).__name__):
                self.assertTrue(models.is_provider_unavailable(error))
                # 그래프는 제공자 오류를 감싸서(raise ... from exc) 올린다. 감싼 오류에서도 원래 오류를 본다.
                wrapped = models.classify_model_error(error)
                wrapped.__cause__ = error
                self.assertTrue(models.is_provider_unavailable(wrapped))

    def test_errors_to_fix_do_not_mean_unavailable(self):
        import anthropic

        request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
        to_fix = [
            ModelAuthenticationError("401"),
            ModelInvalidRequestError("400"),
            anthropic.AuthenticationError("401", response=httpx.Response(401, request=request), body=None),
            anthropic.BadRequestError("400", response=httpx.Response(400, request=request), body=None),
            anthropic.NotFoundError("404", response=httpx.Response(404, request=request), body=None),
            anthropic.ConflictError("409", response=httpx.Response(409, request=request), body=None),
            ValueError("our bug"),
        ]
        for error in to_fix:
            with self.subTest(error=type(error).__name__):
                self.assertFalse(models.is_provider_unavailable(error))
                wrapped = models.classify_model_error(error)
                if wrapped is not None:
                    wrapped.__cause__ = error
                    self.assertFalse(models.is_provider_unavailable(wrapped))


class FallbackSettingsTest(unittest.TestCase):
    def setUp(self):
        for cached in (models.chat_model, models.fallback_chat_model):
            cached.cache_clear()
            self.addCleanup(cached.cache_clear)

    def test_fallback_is_off_by_default(self):
        with patch.dict(os.environ, {"LLM_PROVIDER": "stub"}, clear=True):
            self.assertEqual(models.fallback_provider_name(), "")
            self.assertIsNone(models.fallback_chat_model())

    def test_fallback_same_as_provider_is_rejected(self):
        with patch.dict(os.environ, {"LLM_PROVIDER": "anthropic", "LLM_FALLBACK_PROVIDER": "Anthropic"}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "LLM_FALLBACK_PROVIDER"):
                ChatbotSettings.load()

    def test_fallback_model_follows_its_own_provider_settings(self):
        env = {"LLM_PROVIDER": "anthropic", "ANTHROPIC_API_KEY": "test-only-key", "LLM_FALLBACK_PROVIDER": "openai",
               "OPENAI_API_KEY": "test-only-key", "OPENAI_MODEL": "gpt-6-luna", "CHATBOT_REASONING": "medium"}
        with patch.dict(os.environ, env, clear=True):
            primary = models.chat_model()
            backup = models.fallback_chat_model()
        self.assertEqual(type(primary).__name__, "PawbridgeChatAnthropic")
        self.assertEqual(backup.model_name, "gpt-6-luna")
        self.assertEqual(backup.reasoning, {"effort": "medium"})

    def test_fallback_without_its_key_stops_startup(self):
        env = {**AGENT_ENV, "LLM_PROVIDER": "stub", "LLM_FALLBACK_PROVIDER": "openai"}
        with patch.dict(os.environ, env, clear=True):
            with self.assertRaisesRegex(ChatbotProviderConfigurationError, "OPENAI_API_KEY"):
                with TestClient(app):
                    pass

    def test_unknown_fallback_provider_stops_startup(self):
        env = {**AGENT_ENV, "LLM_PROVIDER": "stub", "LLM_FALLBACK_PROVIDER": "nobody"}
        with patch.dict(os.environ, env, clear=True):
            with self.assertRaises(ChatbotProviderConfigurationError):
                with TestClient(app):
                    pass


if __name__ == "__main__":
    unittest.main()
