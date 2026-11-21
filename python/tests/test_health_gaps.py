import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from pydantic import ValidationError

from app.chatbot.health.gaps import (
    DEFAULT_DECISIONS_PATH,
    GapDecisions,
    Notice,
    find_gaps,
    load_decisions,
    render_markdown,
)
from app.chatbot.health.knowledge import HealthEntry
from app.chatbot.health.retriever import RuleHealthRetriever
from app.entrypoints import health_gap_cli

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


def make_entry(entry_id, aliases, context, species=("dog",), exclude=()):
    return HealthEntry.model_validate(
        {
            "id": entry_id,
            "title": entry_id,
            "species": list(species),
            "match": {"aliases": aliases, "context": context, "exclude": list(exclude)},
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
    make_entry("skin-disease", ["피부병", "피부염"], "any", species=("dog", "cat")),
    make_entry("paralysis-ivdd", ["마비"], "any", species=("dog",)),
    make_entry("nursing-mother", ["수유"], "any", species=("dog", "cat"), exclude=("수유병원",)),
]


def notice(notice_id, text, species="DOG", protected=True):
    return Notice(notice_id=notice_id, species=species, protected=protected, text=text)


def decisions(**lists):
    return GapDecisions.model_validate(lists)


def candidates(report):
    return {
        candidate.word: candidate
        for candidate in report.alias_gap_candidates + report.new_entry_candidates + report.decided_candidates
    }


class KnownGapTest(unittest.TestCase):
    def test_disease_without_entry_becomes_candidate(self):
        report = find_gaps(
            [
                notice("106867", "자궁축농증 수술 후 회복중"),
                notice("2", "자궁축농증 의심", protected=False),
                notice("3", "사람을 좋아하고 온순함"),
            ],
            ENTRIES,
        )

        found = candidates(report)["자궁축농증"]

        self.assertEqual(found.notice_count, 2)
        self.assertEqual(found.protected_count, 1)
        self.assertEqual(found.found_by, "shape")
        self.assertEqual(found.examples[0].notice_id, "106867")
        self.assertIn("자궁축농증", found.examples[0].snippet)
        self.assertIn(found, report.new_entry_candidates)

    def test_counts_notices_without_entry(self):
        report = find_gaps(
            [
                notice("1", "피부병 치료중"),
                notice("2", "사람을 좋아함", protected=False),
                notice("3", "자궁축농증"),
            ],
            ENTRIES,
        )

        self.assertEqual(report.notice_count, 3)
        self.assertEqual(report.protected_count, 2)
        self.assertEqual(report.without_entry_count, 2)
        self.assertEqual(report.protected_without_entry_count, 1)

    def test_word_is_counted_once_per_notice(self):
        report = find_gaps([notice("1", "자궁축농증 의심, 자궁축농증 수술 예정")], ENTRIES, min_count=1)

        self.assertEqual(candidates(report)["자궁축농증"].notice_count, 1)

    def test_candidate_in_notice_that_already_has_entry_is_counted(self):
        report = find_gaps([notice("1", "피부병 치료중이며 자궁축농증 의심")], ENTRIES, min_count=1)

        self.assertEqual(report.without_entry_count, 0)
        self.assertIn("자궁축농증", candidates(report))


class AliasRemovalTest(unittest.TestCase):
    NOTICES = [notice("1", "피부병 심함"), notice("2", "피부병 있음")]

    def test_word_covered_by_alias_is_not_candidate(self):
        report = find_gaps(self.NOTICES, ENTRIES)

        self.assertNotIn("피부병", candidates(report))
        self.assertEqual(report.without_entry_count, 0)

    def test_removing_alias_surfaces_the_word(self):
        entries_without_alias = [entry for entry in ENTRIES if entry.id != "skin-disease"]
        entries_without_alias.append(make_entry("skin-disease", ["피부염"], "any", species=("dog", "cat")))

        report = find_gaps(self.NOTICES, entries_without_alias)

        self.assertEqual(candidates(report)["피부병"].notice_count, 2)
        self.assertEqual(report.without_entry_count, 2)

    def test_word_close_to_existing_alias_is_listed_as_alias_gap(self):
        report = find_gaps([notice("1", "피부질병 있음"), notice("2", "피부질병")], ENTRIES)

        found = candidates(report)["피부질병"]

        self.assertIn(found, report.alias_gap_candidates)
        self.assertEqual(found.similar_aliases[0].alias, "피부병")
        self.assertEqual(found.similar_aliases[0].entry_ids, ("skin-disease",))


class IgnoreListTest(unittest.TestCase):
    NOTICES = [notice("1", "장모종 믹스 추정"), notice("2", "장모종"), notice("3", "믹스 추정"), notice("4", "자궁축농증")]

    def test_ignored_word_is_hidden(self):
        report = find_gaps(
            self.NOTICES,
            ENTRIES,
            decisions(ignore=[{"word": "장모종", "decided_at": "2026-11-21"}, {"word": "믹스", "decided_at": "2026-11-21"}]),
            min_count=1,
        )

        self.assertNotIn("장모종", candidates(report))
        self.assertIn("자궁축농증", candidates(report))
        self.assertEqual(report.ignored_candidate_count, 1)
        self.assertNotIn("믹스", [word.word for word in report.frequent_words])

    def test_without_ignore_list_the_word_is_shown(self):
        report = find_gaps(self.NOTICES, ENTRIES, min_count=1)

        self.assertIn("장모종", candidates(report))
        self.assertIn("믹스", [word.word for word in report.frequent_words])

    def test_min_count_hides_rare_words_and_reports_how_many(self):
        report = find_gaps(self.NOTICES, ENTRIES, min_count=2)

        self.assertIn("장모종", candidates(report))
        self.assertNotIn("자궁축농증", candidates(report))
        self.assertEqual(report.below_min_count, 1)


class SpeciesGapTest(unittest.TestCase):
    def test_alias_with_entry_for_other_species_only(self):
        report = find_gaps(
            [
                notice("1", "후지마비 있음", species="CAT"),
                notice("2", "교통사고로 후구마비", species="고양이", protected=False),
                notice("3", "후지마비", species="DOG"),
            ],
            ENTRIES,
        )

        self.assertEqual(len(report.species_gaps), 1)
        gap = report.species_gaps[0]
        self.assertEqual(gap.alias, "마비")
        self.assertEqual(gap.species, "cat")
        self.assertEqual(gap.other_species_entry_ids, ("paralysis-ivdd",))
        self.assertEqual(gap.notice_count, 2)
        self.assertEqual(gap.protected_count, 1)
        self.assertEqual(dict(gap.surfaces), {"후지마비": 1, "후구마비": 1})
        self.assertEqual(gap.examples[0].species, "고양이")
        self.assertEqual(report.without_entry_count, 2)

    def test_negative_result_without_entry_is_not_species_gap(self):
        entries = [make_entry("heartworm-positive", ["심장사상충"], "positive")]

        report = find_gaps([notice("1", "심장사상충 음성")], entries, min_count=1)

        self.assertEqual(report.species_gaps, ())
        self.assertEqual(candidates(report), {})

    def test_other_species_is_not_reported_as_species_gap(self):
        report = find_gaps([notice("1", "후지마비", species="기타")], ENTRIES)

        self.assertEqual(report.species_gaps, ())


class WordShapeTest(unittest.TestCase):
    def found_words(self, *texts, **options):
        notices = [notice(str(index), text) for index, text in enumerate(texts)]
        return candidates(find_gaps(notices, ENTRIES, min_count=1, **options))

    def test_spaced_and_bracketed_forms_join_the_unspaced_candidate(self):
        found = self.found_words("자궁축농증", "자궁 축농증 수술", "자궁(축농증등 치료")["자궁축농증"]

        self.assertEqual(found.notice_count, 3)
        self.assertEqual(dict(found.surfaces), {"자궁축농증": 1, "자궁 축농증": 1, "자궁(축농증등": 1})

    def test_spaced_form_alone_is_not_joined(self):
        found = self.found_words("자궁 축농증")

        self.assertNotIn("자궁축농증", found)
        self.assertIn("축농증", found)

    def test_words_on_different_lines_are_not_joined(self):
        found = self.found_words("자궁축농증", "자궁\n축농증")

        self.assertEqual(found["자궁축농증"].notice_count, 1)
        self.assertEqual(found["축농증"].notice_count, 1)

    def test_particles_and_state_words_are_stripped(self):
        found = self.found_words("자궁축농증으로 수술", "자궁축농증의심", "구내염증상있음", "방광염이 있음")

        self.assertEqual(found["자궁축농증"].notice_count, 2)
        self.assertIn("구내염", found)
        self.assertNotIn("구내염증상", found)
        self.assertIn("방광염", found)

    def test_symptom_word_without_disease_stem_stays_whole(self):
        found = self.found_words("축농증상 보임")

        self.assertIn("축농증상", found)

    def test_word_before_cue_word_is_candidate(self):
        found = self.found_words("악액질 소견", "악액질소견", "악액질로 진단")["악액질"]

        self.assertEqual(found.notice_count, 3)
        self.assertEqual(found.found_by, "cue")

    def test_cue_words_themselves_are_not_candidates(self):
        found = self.found_words("심장사상충 양성 치료중", "음성 판정", "수술치료 필요", "양성판정")

        self.assertEqual(found, {})

    def test_numbers_and_vaccination_are_not_candidates(self):
        found = self.found_words("5종 접종 완료", "예방접종 완료", "3종백신")

        self.assertEqual(found, {})

    def test_alias_and_exclude_phrase_are_masked(self):
        found = self.found_words("수유병원 앞에서 발견", "심장사상충 양성")

        self.assertEqual(found, {})

    def test_related_words_are_linked(self):
        found = self.found_words("신부전", "만성신부전")

        self.assertEqual(found["신부전"].related_words, ("만성신부전",))
        self.assertEqual(found["만성신부전"].related_words, ("신부전",))

    def test_frequent_words_keep_what_shape_rules_miss(self):
        notices = [notice("1", "녹내장 있음"), notice("2", "녹내장"), notice("3", "녹내장", protected=False)]

        report = find_gaps(notices, ENTRIES)

        self.assertNotIn("녹내장", candidates(report))
        frequent = {word.word: word for word in report.frequent_words}
        self.assertEqual(frequent["녹내장"].notice_count, 3)
        self.assertEqual(frequent["녹내장"].protected_count, 2)


class OrderTest(unittest.TestCase):
    def test_sorted_by_protected_count_then_total(self):
        report = find_gaps(
            [
                notice("1", "방광염", protected=True),
                notice("2", "방광염", protected=True),
                notice("3", "신부전", protected=True),
                notice("4", "신부전", protected=False),
                notice("5", "신부전", protected=False),
                notice("6", "패혈증", protected=True),
                notice("7", "패혈증", protected=False),
            ],
            ENTRIES,
        )

        self.assertEqual([candidate.word for candidate in report.new_entry_candidates], ["방광염", "신부전", "패혈증"])

    def test_protected_examples_come_first(self):
        report = find_gaps(
            [
                notice("1", "방광염", protected=False),
                notice("2", "방광염", protected=False),
                notice("3", "방광염", protected=True),
            ],
            ENTRIES,
        )

        examples = report.new_entry_candidates[0].examples

        self.assertEqual([example.notice_id for example in examples], ["3", "1", "2"])


class DecisionsTest(unittest.TestCase):
    def write(self, content):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "gap_decisions.json"
        path.write_text(json.dumps(content, ensure_ascii=False), encoding="utf-8")
        return path

    def test_default_file_is_valid(self):
        loaded = load_decisions(DEFAULT_DECISIONS_PATH)

        self.assertIsInstance(loaded, GapDecisions)

    def test_decided_words_are_listed_separately(self):
        path = self.write(
            {
                "ignore": [],
                "alias_candidates": [{"word": "피부질병", "decided_at": "2026-11-21", "entry": "skin-disease"}],
                "new_entry_candidates": [
                    {"word": "자궁 축농증", "decided_at": "2026-11-21", "note": "공고 106867"},
                    {"word": "포도막염", "decided_at": "2026-11-21"},
                ],
            }
        )

        report = find_gaps(
            [notice("1", "피부질병"), notice("2", "자궁축농증"), notice("3", "방광염"), notice("4", "방광염")],
            ENTRIES,
            load_decisions(path),
        )

        decided = {candidate.word: candidate.decision for candidate in report.decided_candidates}
        self.assertEqual(decided, {"피부질병": "alias_candidates", "자궁축농증": "new_entry_candidates"})
        self.assertEqual([candidate.word for candidate in report.new_entry_candidates], ["방광염"])
        self.assertEqual(report.alias_gap_candidates, ())
        self.assertEqual(report.absent_decision_words, ("포도막염",))

    def test_same_word_twice_fails(self):
        path = self.write(
            {
                "ignore": [{"word": "장모종", "decided_at": "2026-11-21"}],
                "alias_candidates": [],
                "new_entry_candidates": [{"word": "장모 종", "decided_at": "2026-11-21"}],
            }
        )

        with self.assertRaises(ValueError):
            load_decisions(path)

    def test_unknown_field_fails(self):
        path = self.write({"ignore": [{"word": "장모종", "decided_at": "2026-11-21", "reason": "품종"}]})

        with self.assertRaises(ValidationError):
            load_decisions(path)

    def test_missing_date_fails(self):
        path = self.write({"ignore": [{"word": "장모종"}]})

        with self.assertRaises(ValidationError):
            load_decisions(path)


class RetrieverAgreementTest(unittest.TestCase):
    """빈틈 찾기가 보는 '해설이 붙는다'가 챗봇 검색의 공고 근거 결과와 같은지."""

    TEXTS = [
        "심장사상충 양성, 피부병 있음",
        "심장사상충 음성",
        "심장사상충 검사 예정",
        "피부병 없음",
        "후지마비",
        "수유병원 앞에서 발견",
        "수유중인 어미",
        "사람을 좋아함",
        "",
    ]

    def test_same_verdict_as_search(self):
        retriever = RuleHealthRetriever(ENTRIES)
        for species in ("DOG", "CAT", "기타", None):
            for text in self.TEXTS:
                with self.subTest(text=text, species=species):
                    searched = retriever.search(text, "", species)
                    located = retriever.notice_alias_matches(text, species)
                    attached = {entry_id for match in located for entry_id in match.entry_ids}
                    self.assertEqual({match.entry.id for match in searched}, attached)

    def test_match_reports_where_alias_was_found(self):
        retriever = RuleHealthRetriever(ENTRIES)

        located = retriever.notice_alias_matches("후지마비 있고 피부병", "CAT")

        self.assertEqual([(match.alias, match.start, match.end) for match in located], [("마비", 2, 4), ("피부병", 8, 11)])
        self.assertEqual(located[0].entry_ids, ())
        self.assertEqual(located[0].any_species_entry_ids, ("paralysis-ivdd",))
        self.assertEqual(located[1].entry_ids, ("skin-disease",))


class RenderTest(unittest.TestCase):
    def test_markdown_shows_each_section(self):
        report = find_gaps(
            [
                notice("106867", "자궁축농증 수술 후 회복중"),
                notice("2", "자궁 축농증"),
                notice("3", "후지마비", species="CAT"),
                notice("4", "피부질병"),
                notice("5", "피부질병"),
            ],
            ENTRIES,
        )

        rendered = render_markdown(report)

        self.assertIn("읽은 공고: 5건 (보호중 5건)", rendered)
        self.assertIn("**마비** (고양이) 보호중 1, 전체 1. 다른 종 해설: paralysis-ivdd", rendered)
        self.assertIn("**자궁축농증** 보호중 2, 전체 2", rendered)
        self.assertIn("적힌 꼴: 자궁축농증 1, 자궁 축농증 1", rendered)
        self.assertIn("예시 [106867, 개, 보호중] 자궁축농증 수술 후 회복중", rendered)
        self.assertIn("비슷한 별칭: 피부병(skin-disease)", rendered)


class CliTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.folder = Path(folder.name)

    def write_csv(self, content):
        path = self.folder / "notices.csv"
        path.write_text(content, encoding="utf-8-sig")
        return path

    def test_reads_exported_csv(self):
        path = self.write_csv(
            "id,species,status,special_mark\n"
            '106867,DOG,PROTECT,"자궁축농증 수술\n회복중"\n'
            "2,CAT,ADOPTED,후지마비\n"
            "3,DOG,PROTECT,\n"
        )

        notices = health_gap_cli.read_notices(path, "PROTECT")

        self.assertEqual(
            notices,
            [
                Notice("106867", "DOG", True, "자궁축농증 수술\n회복중"),
                Notice("2", "CAT", False, "후지마비"),
            ],
        )

    def test_missing_column_stops(self):
        path = self.write_csv("id,species,special_mark\n1,DOG,자궁축농증\n")

        with self.assertRaises(SystemExit):
            health_gap_cli.read_notices(path, "PROTECT")

    def run_cli(self, *arguments):
        output = io.StringIO()
        with mock.patch.object(sys, "argv", ["health_gap_cli", *arguments]):
            with contextlib.redirect_stdout(output):
                health_gap_cli.main()
        return output.getvalue()

    def test_writes_markdown_report_with_real_entries(self):
        notices = self.write_csv("id,species,status,special_mark\n1,DOG,PROTECT,자궁축농증\n2,DOG,PROTECT,자궁축농증 의심\n")
        report_path = self.folder / "gaps.md"

        self.run_cli("--notices", str(notices), "--output", str(report_path))

        rendered = report_path.read_text(encoding="utf-8")
        self.assertIn("# 건강 해설 빈틈 후보", rendered)
        self.assertIn("**자궁축농증** 보호중 2, 전체 2", rendered)

    def test_prints_json(self):
        notices = self.write_csv("id,species,status,special_mark\n1,DOG,PROTECT,자궁축농증\n2,DOG,ADOPTED,자궁축농증\n")

        printed = json.loads(self.run_cli("--notices", str(notices), "--format", "json"))

        self.assertEqual(printed["notice_count"], 2)
        found = [item for item in printed["new_entry_candidates"] + printed["alias_gap_candidates"] if item["word"] == "자궁축농증"]
        self.assertEqual(found[0]["protected_count"], 1)


if __name__ == "__main__":
    unittest.main()
