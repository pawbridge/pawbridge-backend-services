import json
import os
import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.chatbot.health.knowledge import HealthEntry
from app.chatbot.health.retriever import RuleHealthRetriever
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
        mock = AsyncMock(return_value=answer)
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

    def test_enabled_with_bad_citation_falls_back_but_keeps_cards(self):
        response, _ = self.post({"HEALTH_RAG_ENABLED": "true"}, '{"answer": "완치됩니다 [1].", "used": [1]}')
        body = response.json()
        self.assertIn("검수된 해설", body["answer"])
        self.assertEqual(body["sources"], [])
        self.assertEqual(len(body["healthCards"]), 1)

    def test_enabled_without_match_adds_no_evidence_rules(self):
        self.payload["animalContext"]["specialMark"] = "순함"
        response, mock = self.post({"HEALTH_RAG_ENABLED": "true"}, "보호소에 확인해 보세요.")
        body = response.json()
        self.assertEqual(body["healthCards"], [])
        self.assertIn("검수된 건강 근거를 찾지 못했습니다", mock.await_args.args[0])
        self.assertFalse(mock.await_args.kwargs["json_output"])


if __name__ == "__main__":
    unittest.main()
