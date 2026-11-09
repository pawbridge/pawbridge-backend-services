"""저장소에 들어 있는 건강 해설 파일 검사.

해설은 운영에서 그대로 근거로 쓰이므로, 형식 검사(knowledge.py)에 더해 저장소에 올리는 규칙을 시험한다.
"""

import json
import re
import unittest

from app.chatbot.health.knowledge import DEFAULT_ENTRIES_DIR, load_entries
from app.chatbot.health.retriever import RuleHealthRetriever

# 검색기의 결과 낱말(retriever.py의 POSITIVE_WORDS, NEGATIVE_WORDS)
RESULT_WORDS = re.compile(r"양성|음성|\(\+\)|\(-\)|감염|확진|의심|증상|소견|positive|negative")


class ShippedEntriesTest(unittest.TestCase):
    def setUp(self):
        self.paths = sorted(DEFAULT_ENTRIES_DIR.glob("*.json"))
        self.raw = [json.loads(path.read_text(encoding="utf-8")) for path in self.paths]

    def test_all_entries_load_and_are_approved(self):
        entries = load_entries()
        self.assertGreater(len(entries), 0)
        self.assertEqual(len(entries), len(self.paths))
        RuleHealthRetriever(entries)

    def test_source_quotes_and_review_notes_are_not_shipped(self):
        # 출처 원문 인용과 내부 검토 메모는 저장소에 올리지 않는다(우리가 쓴 문장, 출처 이름, 링크, 확인일만).
        for entry in self.raw:
            self.assertEqual(entry["review"], {"status": "approved"}, entry["id"])
            for fact in entry["summary"] + entry["cautions"]:
                self.assertEqual(set(fact) - {"text", "sources", "species"}, set(), entry["id"])

    def test_positive_and_negative_aliases_have_no_result_words(self):
        # '파보 양성'처럼 결과 낱말까지 별칭에 넣으면 검색기가 그 덩어리를 먼저 집어
        # 뒤에 '양성'이 남지 않아 문맥을 못 읽는다(띄어 쓴 '파보 양성' 공고를 못 찾음).
        for entry in self.raw:
            if entry["match"]["context"] not in {"positive", "negative"}:
                continue
            for alias in entry["match"]["aliases"]:
                self.assertIsNone(RESULT_WORDS.search(alias.lower()), f"{entry['id']}: {alias}")

    def test_spaced_and_joined_results_find_the_same_entry(self):
        retriever = RuleHealthRetriever(load_entries())
        for notice in ["파보 양성", "파보양성"]:
            self.assertIn("parvovirus-positive", [match.entry.id for match in retriever.search(notice, None, "DOG")], notice)
        for notice in ["심장사상충 음성", "심장사상충음성"]:
            self.assertIn("heartworm-negative", [match.entry.id for match in retriever.search(notice, None, "DOG")], notice)


if __name__ == "__main__":
    unittest.main()
