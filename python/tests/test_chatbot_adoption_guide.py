"""입양 안내 자료와 도구 글. 자료의 주제 이름과 출처 연결이 도구 입력과 맞는지 본다."""

import typing
import unittest

from app.chatbot.agent.adoption import CLOSING, AdoptionTopic, adoption_guide_text, load_guides


class AdoptionGuideTest(unittest.TestCase):
    def test_topic_names_match_the_tool_input(self):
        topics = [item["id"] for item in load_guides()["topics"]]
        self.assertEqual(sorted(topics), sorted(typing.get_args(AdoptionTopic)))

    def test_every_fact_has_a_known_source(self):
        guides = load_guides()
        for item in guides["topics"]:
            for fact in item["facts"]:
                self.assertTrue(fact["sources"], item["id"])
                for name in fact["sources"]:
                    self.assertIn(name, guides["sources"])
                    self.assertTrue(guides["sources"][name]["url"].startswith("https://"))

    def test_text_has_title_facts_sources_and_closing(self):
        text = adoption_guide_text("registration")
        self.assertTrue(text.startswith("[입양 안내: 동물등록]"))
        self.assertIn("월령 2개월 이상인 개", text)
        self.assertIn("(출처: 법제처 찾기쉬운 생활법령정보", text)
        self.assertTrue(text.endswith(CLOSING))

    def test_unknown_topic_is_not_an_error(self):
        self.assertIn("없습니다", adoption_guide_text("unknown"))


if __name__ == "__main__":
    unittest.main()
