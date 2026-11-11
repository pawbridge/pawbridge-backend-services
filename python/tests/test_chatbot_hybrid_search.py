"""에이전트 검색 도구의 하이브리드 판정 규칙. 임베딩 서비스 없이 가짜 벡터 점수로 본다."""

import unittest
from types import SimpleNamespace

from app.chatbot.health.retriever import HealthMatch
from app.chatbot.service import SafeHybridRetriever


def entry(entry_id):
    return SimpleNamespace(id=entry_id)


class FakeRule:
    def __init__(self, found=(), blocked=()):
        self.found = [HealthMatch(entry(item), 1.0, "rule") for item in found]
        self.blocked = set(blocked)

    def search(self, notice_text, question, species):
        return list(self.found)

    def blocked_entry_ids(self, notice_text, question, species):
        return set(self.blocked)


class FakeVector:
    def __init__(self, scores, off_topic=(), fail=False):
        self._scores = scores
        self._off_topic = set(off_topic)
        self._fail = fail

    def is_off_topic(self, text):
        return text in self._off_topic

    def scores(self, text, species):
        if self._fail:
            raise ConnectionError("embedding service down")
        return [(entry(item), score) for item, score in self._scores.get(text, [])]


def retriever(rule, vector):
    return SafeHybridRetriever(rule, SimpleNamespace(_vector=vector))


QUESTION = "다리를 들고 걸어요"


class HybridToolSearchTest(unittest.TestCase):
    def test_adds_query_top_when_question_ranks_it_in_top3(self):
        vector = FakeVector({
            QUESTION: [("amputation", 0.503), ("lameness", 0.495), ("patellar-luxation", 0.449)],
            "파행": [("lameness", 0.547), ("paralysis-ivdd", 0.392)],
        })
        found = retriever(FakeRule(), vector).search_tool(None, "파행", QUESTION, "DOG")
        self.assertEqual([match.entry.id for match in found], ["lameness"])
        self.assertEqual(found[0].basis, "question")

    def test_question_top1_alone_is_not_added(self):
        # 원래 질문의 1위(절단)는 검색어의 1위가 아니므로 넣지 않는다.
        vector = FakeVector({
            QUESTION: [("amputation", 0.503), ("lameness", 0.495)],
            "파행": [("paralysis-ivdd", 0.6)],
        })
        self.assertEqual(retriever(FakeRule(), vector).search_tool(None, "파행", QUESTION, "DOG"), [])

    def test_below_threshold_on_question_is_not_added(self):
        vector = FakeVector({QUESTION: [("lameness", 0.40)], "파행": [("lameness", 0.9)]})
        self.assertEqual(retriever(FakeRule(), vector).search_tool(None, "파행", QUESTION, "DOG"), [])

    def test_rule_blocked_question_skips_vector(self):
        # "외상 없음" 공고처럼 규칙이 해설을 막은 질문에는 벡터를 쓰지 않는다.
        vector = FakeVector({"다친 데 없나요?": [("necrosis", 0.6)], "외상": [("necrosis", 0.6)]})
        found = retriever(FakeRule(blocked={"wound-trauma"}), vector).search_tool("외상 없음", "외상", "다친 데 없나요?", "CAT")
        self.assertEqual(found, [])

    def test_off_topic_question_skips_vector(self):
        vector = FakeVector({"입양비 따로 있나요?": [("prognosis-poor", 0.57)], "입양비": [("prognosis-poor", 0.6)]},
                            off_topic={"입양비 따로 있나요?"})
        self.assertEqual(retriever(FakeRule(), vector).search_tool(None, "입양비", "입양비 따로 있나요?", "DOG"), [])

    def test_rule_results_stay_first_and_are_not_duplicated(self):
        vector = FakeVector({QUESTION: [("lameness", 0.5)], "파행": [("lameness", 0.6)]})
        found = retriever(FakeRule(found=["lameness"]), vector).search_tool(None, "파행", QUESTION, "DOG")
        self.assertEqual([match.entry.id for match in found], ["lameness"])

    def test_embedding_failure_falls_back_to_rule_results(self):
        found = retriever(FakeRule(found=["cough"]), FakeVector({}, fail=True)).search_tool(None, "기침", "콜록거려요", "DOG")
        self.assertEqual([match.entry.id for match in found], ["cough"])


if __name__ == "__main__":
    unittest.main()
