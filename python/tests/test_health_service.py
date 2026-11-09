import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.chatbot import service
from app.chatbot.health.answer import FALLBACK_ANSWER, NO_EVIDENCE_FALLBACK
from app.chatbot.health.knowledge import HealthEntry, load_entries
from app.chatbot.health.retriever import RuleHealthRetriever
from app.chatbot.provider import ChatbotProviderTruncatedError
from app.entrypoints.chatbot_api import app

SOURCES = {
    "a": {"org": "기관 A", "title": "문서 A", "url": "https://example.org/a", "tier": "A", "language": "ko", "checked_at": "2026-11-06"},
}

ENTRY = HealthEntry.model_validate(
    {
        "id": "heartworm-positive",
        "title": "심장사상충 양성",
        "species": ["dog"],
        "match": {"aliases": ["심장사상충"], "context": "positive"},
        "summary": [{"text": "심장사상충은 모기가 옮기는 기생충입니다.", "sources": ["a"]}],
        "cautions": [{"text": "치료 중에는 운동을 제한해야 합니다.", "sources": ["a"]}],
        "ask_shelter": ["치료 단계"],
        "sources": SOURCES,
        "review": {"status": "approved"},
    }
)


class StartupLoadTest(unittest.TestCase):
    def setUp(self):
        service.health_retriever.cache_clear()
        self.addCleanup(service.health_retriever.cache_clear)

    def load_from(self, folder):
        return patch("app.chatbot.service.load_entries", lambda: load_entries(Path(folder)))

    def test_enabled_with_bad_entry_stops_startup(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "broken.json").write_text('{"id": "broken"}', encoding="utf-8")
            with self.load_from(folder), patch.dict(os.environ, {"INTERNAL_API_KEY": "k", "HEALTH_RAG_ENABLED": "true"}):
                with self.assertRaises(ValidationError):
                    with TestClient(app):
                        pass

    def test_enabled_without_approved_entries_stops_startup(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.load_from(folder), patch.dict(os.environ, {"INTERNAL_API_KEY": "k", "HEALTH_RAG_ENABLED": "true"}):
                with self.assertRaises(RuntimeError):
                    with TestClient(app):
                        pass

    def test_disabled_does_not_read_entries(self):
        with patch("app.chatbot.service.load_entries", side_effect=AssertionError("읽으면 안 됨")):
            with patch.dict(os.environ, {"INTERNAL_API_KEY": "k", "HEALTH_RAG_ENABLED": "false"}):
                with TestClient(app):
                    pass


class HealthRagServiceTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)
        self.payload = {
            "animalContext": {"species": "DOG", "specialMark": "심장사상충 양성, 순함"},
            "recentMessages": [],
            "question": "산책 많이 시켜도 되나요?",
        }
        self.retriever = patch("app.chatbot.service.health_retriever", return_value=RuleHealthRetriever([ENTRY]))
        self.retriever.start()
        self.addCleanup(self.retriever.stop)

    def post(self, env, answer):
        mock = answer if isinstance(answer, AsyncMock) else AsyncMock(return_value=answer)
        with patch.dict(os.environ, {"INTERNAL_API_KEY": "k", "LLM_PROVIDER": "stub", **env}, clear=False):
            with patch("app.chatbot.stub_provider.StubChatbotProvider.generate_answer", mock):
                response = self.client.post("/internal/chatbot/messages", headers={"X-Internal-Api-Key": "k"}, json=self.payload)
        return response, mock

    def test_disabled_keeps_old_behavior(self):
        response, mock = self.post({"HEALTH_RAG_ENABLED": "false"}, "보호소에 확인해 보세요.")
        body = response.json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(body["answer"], "보호소에 확인해 보세요.")
        self.assertEqual(body["sources"], [])
        self.assertEqual(body["healthCards"], [])
        prompt = mock.await_args.args[0]
        self.assertNotIn("검수된 건강 근거", prompt)
        self.assertFalse(mock.await_args.kwargs["json_output"])

    def test_enabled_adds_evidence_sources_and_cards(self):
        answer = json.dumps({"answer": "치료 중에는 운동을 제한해야 합니다 [2].", "used": [2]}, ensure_ascii=False)
        response, mock = self.post({"HEALTH_RAG_ENABLED": "true"}, answer)
        body = response.json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(body["answer"], "치료 중에는 운동을 제한해야 합니다 [2].")
        self.assertEqual([source["url"] for source in body["sources"]], ["https://example.org/a"])
        self.assertEqual(body["healthCards"][0]["id"], "heartworm-positive")
        self.assertIn("[검수된 건강 근거]", mock.await_args.args[0])
        self.assertTrue(mock.await_args.kwargs["json_output"])

    def test_enabled_with_unknown_citation_falls_back_but_keeps_cards(self):
        response, _ = self.post({"HEALTH_RAG_ENABLED": "true"}, '{"answer": "운동을 줄이세요 [9].", "used": [9]}')
        body = response.json()
        self.assertEqual(body["answer"], FALLBACK_ANSWER)
        self.assertEqual(body["sources"], [])
        self.assertEqual(len(body["healthCards"]), 1)

    def test_enabled_with_plain_text_reply_falls_back_instead_of_leaking(self):
        response, _ = self.post({"HEALTH_RAG_ENABLED": "true"}, "JSON이 아닌 글 [1]")
        self.assertEqual(response.json()["answer"], FALLBACK_ANSWER)

    def test_enabled_with_truncated_reply_falls_back_with_cards(self):
        truncated = AsyncMock(side_effect=ChatbotProviderTruncatedError("cut"))
        response, _ = self.post({"HEALTH_RAG_ENABLED": "true"}, truncated)
        body = response.json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(body["answer"], FALLBACK_ANSWER)
        self.assertEqual(len(body["healthCards"]), 1)

    def test_disabled_truncated_reply_is_still_502(self):
        truncated = AsyncMock(side_effect=ChatbotProviderTruncatedError("cut"))
        response, _ = self.post({"HEALTH_RAG_ENABLED": "false"}, truncated)
        self.assertEqual(response.status_code, 502)

    def test_question_only_match_is_marked_general(self):
        self.payload["animalContext"]["specialMark"] = "순함"
        self.payload["question"] = "심장사상충 있으면 어떡해요?"
        answer = json.dumps({"answer": "모기가 옮기는 기생충입니다 [1].", "used": [1]}, ensure_ascii=False)
        response, mock = self.post({"HEALTH_RAG_ENABLED": "true"}, answer)
        self.assertEqual(response.json()["healthCards"][0]["basis"], "question")
        self.assertIn("공고에는 없는 질문 관련 일반 정보", mock.await_args.args[0])

    def test_enabled_without_match_checks_medical_phrases(self):
        self.payload["animalContext"]["specialMark"] = "순함"
        response, _ = self.post({"HEALTH_RAG_ENABLED": "true"}, "하트가드를 먹이세요. 곧 회복됩니다.")
        self.assertEqual(response.json()["answer"], NO_EVIDENCE_FALLBACK)

    def test_enabled_without_match_keeps_everyday_answer(self):
        self.payload["animalContext"]["specialMark"] = "순함"
        response, _ = self.post({"HEALTH_RAG_ENABLED": "true"}, "아파트에서 키워도 괜찮습니다. 산책은 하루 두 번이 좋아요.")
        self.assertEqual(response.json()["answer"], "아파트에서 키워도 괜찮습니다. 산책은 하루 두 번이 좋아요.")

    def test_enabled_without_match_adds_no_evidence_rules(self):
        self.payload["animalContext"]["specialMark"] = "순함"
        response, mock = self.post({"HEALTH_RAG_ENABLED": "true"}, "보호소에 확인해 보세요.")
        body = response.json()
        self.assertEqual(body["healthCards"], [])
        self.assertIn("검수된 건강 근거를 찾지 못했습니다", mock.await_args.args[0])
        self.assertFalse(mock.await_args.kwargs["json_output"])


if __name__ == "__main__":
    unittest.main()
