"""저장소에 들어 있는 건강 해설 파일 검사.

해설은 운영에서 그대로 근거로 쓰이므로, 형식 검사(knowledge.py)에 더해 저장소에 올리는 규칙을 시험한다.
"""

import json
import re
import unittest

from app.chatbot.health.answer import _forbidden_in
from app.chatbot.health.knowledge import DEFAULT_ENTRIES_DIR, load_entries
from app.chatbot.health.retriever import RuleHealthRetriever

# 검색기의 결과 낱말(retriever.py의 POSITIVE_WORDS, NEGATIVE_WORDS)
RESULT_WORDS = re.compile(r"양성|음성|\(\+\)|\(-\)|감염|확진|의심|증상|소견|positive|negative")


class ShippedEntriesTest(unittest.TestCase):
    def setUp(self):
        self.paths = sorted(DEFAULT_ENTRIES_DIR.glob("*.json"))
        self.raw = [json.loads(path.read_text(encoding="utf-8")) for path in self.paths]

    def test_entry_sentences_pass_the_answer_check(self):
        # 해설 파일 검사(knowledge.py)와 답 검사(answer.py)의 금지 표현은 따로 있다. 해설 문장을 그대로 옮긴 답이
        # 답 검사에 걸리면 그 해설을 쓰는 질문은 모두 대체 답변이 된다(11/12 실명, 심장사상충 해설).
        for entry in load_entries():
            sentences = [fact.text for fact in entry.summary + entry.cautions] + entry.ask_shelter
            for text in sentences:
                # 모델처럼 근거 번호를 문장 끝 마침표 앞에 붙인다(마침표 뒤에 붙이면 근거 문장으로 보지 않는다).
                cited = text[:-1] + " [1]." if text.endswith(".") else text + " [1]"
                with self.subTest(entry=entry.id, text=text):
                    self.assertEqual(_forbidden_in(cited), [])

    def test_entry_sentences_do_not_assume_the_notice_says_it(self):
        # 해설은 공고에서도, 질문에서도 찾힌다. "공고의 범백 표기는"처럼 공고에 그 말이 있다고 단정하면
        # 공고가 빈 동물에게도 모델이 "공고에 범백 표기가 있다"는 식으로 옮긴다(11/12 h057, 16번 중 15번).
        # 공고를 말할 때는 "공고에 … 적혀 있다면"처럼 조건으로 쓴다.
        for entry in load_entries():
            for fact in entry.summary + entry.cautions:
                with self.subTest(entry=entry.id, text=fact.text):
                    self.assertNotIn("공고의", fact.text)

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
