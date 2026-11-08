import unittest

from app.chatbot.health.knowledge import HealthEntry
from app.chatbot.health.retriever import RuleHealthRetriever, detect_context

SOURCE = {
    "src": {
        "org": "시험 기관",
        "title": "시험 문서",
        "url": "https://example.org/doc",
        "tier": "A",
        "language": "ko",
        "checked_at": "2026-11-06",
    }
}


def make_entry(entry_id, aliases, context, species=("dog",)):
    return HealthEntry.model_validate(
        {
            "id": entry_id,
            "title": entry_id,
            "species": list(species),
            "match": {"aliases": aliases, "context": context},
            "summary": [{"text": "일반 정보입니다.", "sources": ["src"]}],
            "cautions": [{"text": "동물병원 진료가 필요합니다.", "sources": ["src"]}],
            "ask_shelter": [],
            "sources": SOURCE,
            "review": {"status": "approved"},
        }
    )


ENTRIES = [
    make_entry("heartworm-positive", ["심장사상충", "사상충"], "positive"),
    make_entry("heartworm-negative", ["심장사상충", "사상충"], "negative"),
    make_entry("parvovirus-positive", ["파보"], "positive"),
    make_entry("infection-test-negative", ["파보", "코로나", "지알디아"], "negative"),
    make_entry("giardia-positive", ["지알디아"], "positive"),
    make_entry("vaccination-notation-dog", ["종합백신", "코로나장염"], "vaccination"),
    make_entry("rabies-vaccination", ["광견병"], "vaccination", species=("dog", "cat")),
    make_entry("skin-disease", ["피부병", "피부염"], "any", species=("dog", "cat")),
    make_entry("cat-upper-respiratory", ["허피스", "콧물", "눈곱"], "any", species=("cat",)),
    make_entry("conjunctivitis", ["결막염", "눈곱"], "any", species=("dog", "cat")),
]


def ids(matches):
    return [match.entry.id for match in matches]


class DetectContextTest(unittest.TestCase):
    def test_negative_after_term(self):
        text = "심장사상충&파보 음성, 종합&광견"
        self.assertEqual(detect_context(text, 0, 5), "negative")
        self.assertEqual(detect_context(text, 6, 8), "negative")

    def test_positive_with_hyphen(self):
        text = "사상충-양성, 접종(종합백신/코로나장염/광견병)"
        self.assertEqual(detect_context(text, 0, 3), "positive")

    def test_inside_vaccine_parentheses(self):
        text = "사상충-양성, 접종(종합백신/코로나장염/광견병)"
        start = text.index("코로나장염")
        self.assertEqual(detect_context(text, start, start + 5), "vaccination")

    def test_separator_stops_context(self):
        text = "파보, 심장사상충 양성"
        self.assertIsNone(detect_context(text, 0, 2))


class RuleHealthRetrieverTest(unittest.TestCase):
    def setUp(self):
        self.retriever = RuleHealthRetriever(ENTRIES)

    def test_negative_notice_never_gets_positive_entry(self):
        matches = self.retriever.search("심장사상충&파보 음성, 종합&광견", None, "DOG")
        self.assertEqual(ids(matches), ["heartworm-negative", "infection-test-negative"])

    def test_positive_notice_with_vaccine_list(self):
        notice = "사상충-양성, 접종(종합백신/코로나장염/광견병), 피부병"
        matches = self.retriever.search(notice, None, "DOG")
        # 병이 있는 것(사상충 양성, 피부병)을 접종 표기보다 앞에 두고, 최대 3개라 광견병 표기는 밀린다.
        self.assertEqual(ids(matches), ["heartworm-positive", "skin-disease", "vaccination-notation-dog"])

    def test_vaccine_corona_is_not_infection(self):
        matches = self.retriever.search("접종(코로나장염)", None, "DOG")
        self.assertEqual(ids(matches), ["vaccination-notation-dog"])

    def test_question_without_context_uses_notice_context(self):
        matches = self.retriever.search("심장사상충 음성", "심장사상충이 뭐예요?", "DOG")
        self.assertEqual(ids(matches), ["heartworm-negative"])
        self.assertIn("질문", matches[0].reason)

    def test_question_without_any_context_gets_disease_entry(self):
        matches = self.retriever.search(None, "사상충 있으면 산책해도 돼요?", "DOG")
        self.assertEqual(ids(matches), ["heartworm-positive"])

    def test_longer_alias_wins(self):
        matches = self.retriever.search("심장사상충양성", None, "DOG")
        self.assertEqual(ids(matches), ["heartworm-positive"])

    def test_species_filter(self):
        self.assertEqual(ids(self.retriever.search("콧물", None, "DOG")), [])
        self.assertEqual(ids(self.retriever.search("콧물", None, "CAT")), ["cat-upper-respiratory"])

    def test_shared_alias_returns_every_matching_entry(self):
        self.assertEqual(
            ids(self.retriever.search("눈곱, 콧물", None, "CAT")),
            ["cat-upper-respiratory", "conjunctivitis"],
        )
        self.assertEqual(ids(self.retriever.search("눈곱", None, "DOG")), ["conjunctivitis"])

    def test_other_species_gets_nothing(self):
        self.assertEqual(ids(self.retriever.search("피부병, 콧물", None, "ETC")), [])

    def test_unknown_species_keeps_all(self):
        self.assertEqual(ids(self.retriever.search("콧물", None, None)), ["cat-upper-respiratory"])

    def test_no_terms_returns_empty(self):
        self.assertEqual(self.retriever.search("순함, 사람 좋아함", "보호소 몇 시까지 해요?", "DOG"), [])

    def test_name_without_result_in_notice_is_not_positive(self):
        self.assertEqual(ids(self.retriever.search("8월25일 파보,홍역키트 검사, 종합백신", None, "DOG")), ["vaccination-notation-dog"])

    def test_listed_terms_share_following_result(self):
        self.assertEqual(ids(self.retriever.search("파보, 코로나 음성", None, "DOG")), ["infection-test-negative"])

    def test_non_test_term_does_not_inherit_result(self):
        retriever = RuleHealthRetriever([*ENTRIES, make_entry("mammary-tumor", ["유선종양"], "any")])
        self.assertEqual(
            ids(retriever.search("유선종양,심장사상충 음성", None, "DOG")),
            ["mammary-tumor", "heartworm-negative"],
        )

    def test_question_does_not_guess_positive_when_notice_has_no_result(self):
        matches = self.retriever.search("8월25일 파보,홍역키트 검사", "파보 검사 결과가 뭐예요?", "DOG")
        self.assertNotIn("parvovirus-positive", ids(matches))

    def test_absent_state_gets_nothing(self):
        self.assertEqual(ids(self.retriever.search("외상없음, 피부병 없음", None, "DOG")), [])

    def test_excluded_phrase_is_ignored(self):
        data = make_entry("pregnant-nursing-mother", ["수유", "임신"], "any").model_dump()
        data["match"]["exclude"] = ["수유동", "수유역"]
        retriever = RuleHealthRetriever([HealthEntry.model_validate(data)])
        self.assertEqual(ids(retriever.search("수유동에서 구조됨", None, "DOG")), [])
        self.assertEqual(ids(retriever.search("수유중, 수유역 근처", None, "DOG")), ["pregnant-nursing-mother"])

    def test_run_together_terms(self):
        self.assertEqual(ids(self.retriever.search("심장사상충양성입니다", None, "DOG")), ["heartworm-positive"])
        self.assertEqual(ids(self.retriever.search("심장사상충음성입니다", None, "DOG")), ["heartworm-negative"])

    def test_absent_word_far_away_belongs_to_other_term(self):
        matches = self.retriever.search("허피스로 기력이 없고 눈에 염증", None, "CAT")
        self.assertIn("cat-upper-respiratory", ids(matches))

    def test_prevention_medicine_is_not_disease(self):
        self.assertEqual(ids(self.retriever.search("사상충+진드기 예방약복용", None, "DOG")), [])

    def test_single_letter_alias_needs_boundary(self):
        retriever = RuleHealthRetriever([make_entry("external-parasites", ["옴", "진드기"], "any")])
        self.assertEqual(ids(retriever.search("목쪽에 진물이 나옴", None, "DOG")), [])
        self.assertEqual(ids(retriever.search("옴, 피부병", None, "DOG")), ["external-parasites"])

    def test_space_separated_results_belong_to_their_own_term(self):
        self.assertEqual(ids(self.retriever.search("심장사상충 양성 파보 음성", None, "DOG")), ["heartworm-positive", "infection-test-negative"])
        self.assertNotIn("heartworm-negative", ids(self.retriever.search("심장사상충 치료중 파보 음성", None, "DOG")))

    def test_question_synonym_follows_notice_context(self):
        matches = self.retriever.search("심장사상충 음성", "사상충 걱정돼요", "DOG")
        self.assertEqual(ids(matches), ["heartworm-negative"])

    def test_vaccine_word_after_next_alias_still_counts(self):
        self.assertEqual(ids(self.retriever.search("광견병 종합백신 접종", None, "DOG")), ["rabies-vaccination", "vaccination-notation-dog"])
        self.assertEqual(ids(self.retriever.search("심장사상충 양성 종합백신 접종", None, "DOG")), ["heartworm-positive", "vaccination-notation-dog"])

    def test_absent_after_positive_word(self):
        self.assertEqual(ids(self.retriever.search("파보 의심 없음, 피부병 증상 없음", None, "DOG")), [])

    def test_notice_results_keep_disease_entries_before_vaccination(self):
        notice = "접종(종합백신/코로나장염/광견병), 피부병, 사상충 양성, 파보 양성"
        self.assertEqual(ids(self.retriever.search(notice, None, "DOG")), ["skin-disease", "heartworm-positive", "parvovirus-positive"])

    def test_at_most_three_results(self):
        notice = "사상충 양성, 파보 양성, 지알디아 양성, 피부병"
        self.assertEqual(len(self.retriever.search(notice, None, "DOG")), 3)


class KnowledgeValidationTest(unittest.TestCase):
    def test_forbidden_phrase_rejected(self):
        with self.assertRaises(ValueError):
            HealthEntry.model_validate(
                {
                    **make_entry("x", ["x"], "any").model_dump(),
                    "summary": [{"text": "완치됩니다.", "sources": ["src"]}],
                }
            )

    def test_facts_for_species(self):
        data = make_entry("x", ["x"], "any", species=("dog", "cat")).model_dump()
        data["summary"].append({"text": "고양이에만 해당합니다.", "sources": ["src"], "species": ["cat"]})
        entry = HealthEntry.model_validate(data)
        dog_summary, _ = entry.facts_for("dog")
        cat_summary, _ = entry.facts_for("cat")
        self.assertEqual(len(dog_summary), 1)
        self.assertEqual(len(cat_summary), 2)

    def test_fact_species_must_be_in_entry(self):
        data = make_entry("x", ["x"], "any", species=("dog",)).model_dump()
        data["summary"][0]["species"] = ["cat"]
        with self.assertRaises(ValueError):
            HealthEntry.model_validate(data)

    def test_needs_tier_a_source(self):
        data = make_entry("x", ["x"], "any").model_dump()
        data["sources"]["src"]["tier"] = "B"
        with self.assertRaises(ValueError):
            HealthEntry.model_validate(data)


if __name__ == "__main__":
    unittest.main()
