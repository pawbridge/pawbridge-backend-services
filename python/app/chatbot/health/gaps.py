"""건강 해설 빈틈 찾기.

공고 특이사항에 적혀 있는데 해설이 붙지 않는 병명, 상태 낱말을 보호중인 동물 수 순으로 모은다.
해설이 붙는지는 챗봇과 같은 규칙 검색기(RuleHealthRetriever)로 본다. 모델 호출과 네트워크를 쓰지 않는다.
후보를 보고 무시할지, 별칭을 더할지, 새 해설을 쓸지는 사람이 정한다. 이 모듈은 해설과 별칭을 고치지 않는다.
"""

import difflib
import json
import re
from collections import Counter
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Iterable

from pydantic import BaseModel, ConfigDict, Field

from app.chatbot.health.knowledge import HealthEntry
from app.chatbot.health.retriever import (
    OTHER_SPECIES,
    RuleHealthRetriever,
    normalize_species,
    normalize_text,
)

DEFAULT_DECISIONS_PATH = Path(__file__).resolve().parent / "gap_decisions.json"

# 병명, 상태 낱말이 흔히 끝나는 꼴. 품종('장모종') 같은 낱말도 걸리므로 사람이 무시 목록으로 걸러 낸다.
DISEASE_ENDINGS = (
    "증상",
    "결석",
    "농양",
    "궤양",
    "마비",
    "부전",
    "감염",
    "질환",
    "탈구",
    "골절",
    "종양",
    "장애",
    "손상",
    "파열",
    "기형",
    "탈출",
    "빈혈",
    "황달",
    "염",
    "증",
    "종",
    "병",
    "암",
    "충",
)
# 끝 꼴은 맞지만 병명이 아닌 것. 접종 표기는 접종 해설이 따로 다룬다.
NOT_DISEASE_ENDINGS = ("접종",)
# 병명 뒤에 붙여 쓰는 조사
PARTICLES = ("으로", "에서", "이며", "이고", "이나", "이", "가", "은", "는", "을", "를", "과", "와", "도", "의", "에", "로", "등")
# 병명 가까이에 오는 말. '악액질 소견'처럼 끝 꼴 규칙에 안 걸리는 병명을 이 말의 앞 낱말로 잡는다.
CUE_WORDS = ("의심됨", "치료중", "진단", "의심", "소견", "증상", "치료", "수술", "양성", "확진", "판정")
# 병명 뒤에 붙여 쓰는 상태 표현. 떼어 내기만 하고 병명의 단서로 쓰지는 않는다.
STATE_WORDS = ("있으며", "있음", "있슴", "있는", "있고", "있어", "심함", "추정", "보임", "같음", "발견", "관찰", "환자")
TRAILING_WORDS = tuple(sorted(set(PARTICLES + CUE_WORDS + STATE_WORDS), key=len, reverse=True))
# 단서 말 앞에 와도 병명이 아닌 것. 단서 말끼리 이어 쓴 것('양성 치료중', '수술 소견')과 검사 결과 말이다.
NOT_CUE_NAMES = frozenset(TRAILING_WORDS) | {"음성"}
MAX_STRIPS = 3
MIN_WORD_LENGTH = 2

# 별칭과 제외 구절을 지운 자리. 낱말이 이 자리를 건너 이어지지 않게 한다.
MASK = "\x00"
TOKEN = re.compile(r"[0-9a-z가-힣]+")
HANGUL_WORD = re.compile(r"[가-힣]+")
CUE_NAME = re.compile(r"[a-z가-힣]+")
HAS_HANGUL = re.compile(r"[가-힣]")
# '자궁 축농증', '자궁(축농증'처럼 한 낱말을 띄거나 괄호로 나눠 쓴 사이
JOIN_GAP = re.compile(r"[ \t(\[]{1,3}")

SIMILAR_ALIAS_CUTOFF = 0.7
MAX_SIMILAR_ALIASES = 3
MAX_EXAMPLES = 3
MAX_SURFACES = 5
SNIPPET_MARGIN = 30

SPECIES_LABELS = {"dog": "개", "cat": "고양이", OTHER_SPECIES: "기타", None: "종 모름"}


@dataclass(frozen=True)
class Notice:
    """공고 하나. protected는 지금 보호중인지."""

    notice_id: str
    species: str | None
    protected: bool
    text: str


class GapDecision(BaseModel):
    """사람이 후보 낱말 하나에 내린 결정."""

    model_config = ConfigDict(extra="forbid")

    word: str = Field(min_length=1)
    decided_at: date
    # 별칭 후보일 때 별칭을 더할 해설 id
    entry: str | None = None
    note: str | None = None


class GapDecisions(BaseModel):
    """사람이 정한 목록. 무시할 낱말은 결과에서 빼고, 나머지는 '이미 정한 것'으로 따로 보여 준다."""

    model_config = ConfigDict(extra="forbid")

    ignore: list[GapDecision] = Field(default_factory=list)
    alias_candidates: list[GapDecision] = Field(default_factory=list)
    new_entry_candidates: list[GapDecision] = Field(default_factory=list)


@dataclass(frozen=True)
class GapExample:
    notice_id: str
    species: str
    protected: bool
    snippet: str


@dataclass(frozen=True)
class SimilarAlias:
    alias: str
    entry_ids: tuple[str, ...]


@dataclass(frozen=True)
class GapCandidate:
    """해설이 붙지 않은 자리에서 나온 낱말 하나."""

    word: str
    # shape: 낱말 끝 꼴로 잡음. cue: '진단', '의심' 같은 말의 앞 낱말이라 잡음.
    found_by: str
    protected_count: int
    notice_count: int
    # 공고에 실제로 적힌 꼴과 공고 수
    surfaces: tuple[tuple[str, int], ...]
    examples: tuple[GapExample, ...]
    # 글자가 비슷한 기존 별칭. 있으면 별칭만 빠진 것일 수 있다.
    similar_aliases: tuple[SimilarAlias, ...] = ()
    # 이 낱말을 품거나 이 낱말에 들어 있는 다른 후보
    related_words: tuple[str, ...] = ()
    # 사람이 이미 정한 갈래(alias_candidates, new_entry_candidates). 아직 안 정했으면 None.
    decision: str | None = None


@dataclass(frozen=True)
class SpeciesGap:
    """별칭은 찾았지만 그 종의 해설이 없는 자리."""

    alias: str
    species: str
    other_species_entry_ids: tuple[str, ...]
    protected_count: int
    notice_count: int
    surfaces: tuple[tuple[str, int], ...]
    examples: tuple[GapExample, ...]


@dataclass(frozen=True)
class FrequentWord:
    word: str
    protected_count: int
    notice_count: int


@dataclass(frozen=True)
class GapReport:
    notice_count: int
    protected_count: int
    # 규칙 검색기가 해설을 하나도 붙이지 못한 공고
    without_entry_count: int
    protected_without_entry_count: int
    species_gaps: tuple[SpeciesGap, ...]
    # 아직 정하지 않은 후보 가운데 비슷한 별칭이 있는 것(별칭만 없는 것일 수 있음)
    alias_gap_candidates: tuple[GapCandidate, ...]
    # 아직 정하지 않은 후보 가운데 비슷한 별칭이 없는 것(해설이 아예 없는 것일 수 있음)
    new_entry_candidates: tuple[GapCandidate, ...]
    # 사람이 별칭 후보나 새 해설 후보로 이미 정한 것
    decided_candidates: tuple[GapCandidate, ...]
    # 정해 두었지만 이번 공고에는 나오지 않은 낱말(이미 반영했거나 공고가 바뀜)
    absent_decision_words: tuple[str, ...]
    # 후보로 잡히지 않은 나머지 낱말 가운데 자주 나오는 것. 끝 꼴 규칙이 놓친 병명을 사람이 훑어보는 용도다.
    frequent_words: tuple[FrequentWord, ...]
    ignored_candidate_count: int
    below_min_count: int
    min_count: int


@dataclass(frozen=True)
class _Token:
    start: int
    end: int
    word: str
    # 다음 낱말과 띄어쓰기나 괄호로만 떨어져 있는지
    joins_next: bool


@dataclass
class _Tally:
    notice_count: int = 0
    protected_count: int = 0
    surfaces: Counter = field(default_factory=Counter)
    protected_examples: list[GapExample] = field(default_factory=list)
    other_examples: list[GapExample] = field(default_factory=list)
    found_by: str = "cue"

    def add(self, notice: Notice, surface: str, example: GapExample) -> None:
        self.notice_count += 1
        self.surfaces[surface] += 1
        if notice.protected:
            self.protected_count += 1
            if len(self.protected_examples) < MAX_EXAMPLES:
                self.protected_examples.append(example)
        elif len(self.other_examples) < MAX_EXAMPLES:
            self.other_examples.append(example)

    def examples(self) -> tuple[GapExample, ...]:
        # 지금 보호중인 동물의 공고를 먼저 보여 준다.
        return tuple((self.protected_examples + self.other_examples)[:MAX_EXAMPLES])

    def top_surfaces(self) -> tuple[tuple[str, int], ...]:
        return tuple(self.surfaces.most_common(MAX_SURFACES))


def load_decisions(path: Path = DEFAULT_DECISIONS_PATH) -> GapDecisions:
    """사람이 정한 목록 파일을 읽는다. 같은 낱말이 두 번 있으면 어느 결정이 맞는지 알 수 없으므로 실패시킨다."""
    decisions = GapDecisions.model_validate(json.loads(path.read_text(encoding="utf-8")))
    seen: set[str] = set()
    for decision in decisions.ignore + decisions.alias_candidates + decisions.new_entry_candidates:
        key = decision_key(decision.word)
        if key in seen:
            raise ValueError(f"{path.name}: '{decision.word}'이(가) 두 번 있습니다")
        seen.add(key)
    return decisions


def decision_key(word: str) -> str:
    """목록 파일의 낱말을 후보 낱말과 견줄 수 있는 꼴로 바꾼다. 후보 낱말에는 띄어쓰기가 없다."""
    return re.sub(r"\s+", "", normalize_text(word))


def find_gaps(
    notices: Iterable[Notice],
    entries: list[HealthEntry],
    decisions: GapDecisions | None = None,
    min_count: int = 2,
    frequent_word_limit: int = 100,
) -> GapReport:
    """공고에서 해설 빈틈 후보를 찾는다. min_count는 후보로 보여 줄 최소 공고 수다."""
    decisions = decisions or GapDecisions()
    retriever = RuleHealthRetriever(entries)
    exclude_phrases = _exclude_phrases(entries)

    notice_count = 0
    protected_count = 0
    without_entry_count = 0
    protected_without_entry_count = 0
    species_tallies: dict[tuple[str, str], _Tally] = {}
    species_entry_ids: dict[tuple[str, str], set[str]] = {}
    scanned: list[tuple[Notice, str, list[_Token]]] = []
    known_names: set[str] = set()

    # 1회차: 공고마다 해설이 붙는지 보고, 별칭과 제외 구절을 지운 나머지를 낱말로 나눈다.
    for notice in notices:
        text = normalize_text(notice.text)
        matches = retriever.notice_alias_matches(notice.text, notice.species)
        notice_count += 1
        if notice.protected:
            protected_count += 1
        if not any(match.entry_ids for match in matches):
            without_entry_count += 1
            if notice.protected:
                protected_without_entry_count += 1

        species = normalize_species(notice.species)
        seen_species_gaps: set[tuple[str, str]] = set()
        for match in matches:
            if species not in {"dog", "cat"}:
                continue
            if match.entry_ids or not match.any_species_entry_ids:
                continue
            key = (match.alias, species)
            species_entry_ids.setdefault(key, set()).update(match.any_species_entry_ids)
            if key in seen_species_gaps:
                continue
            seen_species_gaps.add(key)
            surface = _whole_word(text, match.start, match.end)
            example = _example(notice, text, match.start, match.end)
            species_tallies.setdefault(key, _Tally()).add(notice, surface, example)

        spans = [(match.start, match.end) for match in matches]
        tokens = _tokenize(_mask(text, spans, exclude_phrases))
        for token in tokens:
            reduced = _reduce(token.word)
            if reduced:
                known_names.add(reduced[0])
        scanned.append((notice, text, tokens))

    # 2회차: 낱말을 후보로 센다. 혼자서는 후보가 아닌 낱말은 다음 낱말과 붙여 보고, 붙인 꼴이 다른 곳에 한 낱말로 나오면 그 후보로 센다.
    candidate_tallies: dict[str, _Tally] = {}
    word_notice_counts: Counter = Counter()
    word_protected_counts: Counter = Counter()
    for notice, text, tokens in scanned:
        counted_names: set[str] = set()
        leftover_words: set[str] = set()
        index = 0
        while index < len(tokens):
            token = tokens[index]
            following = tokens[index + 1] if token.joins_next and index + 1 < len(tokens) else None
            found = _reduce(token.word)
            end = token.end
            consumed = 1
            if not found and following:
                joined = _reduce(token.word + following.word)
                if joined and joined[0] in known_names:
                    found = joined
                    end = following.end
                    consumed = 2
            if not found and following and following.word.startswith(CUE_WORDS):
                name = _strip_particles(token.word)
                if _is_cue_name(name):
                    found = (name, "cue")
            if found:
                name, found_by = found
                if name not in counted_names:
                    counted_names.add(name)
                    tally = candidate_tallies.setdefault(name, _Tally())
                    tally.add(notice, text[token.start:end], _example(notice, text, token.start, end))
                    if found_by == "shape":
                        tally.found_by = "shape"
            else:
                word = _strip_particles(token.word)
                if len(word) >= MIN_WORD_LENGTH and HAS_HANGUL.search(word):
                    leftover_words.add(word)
            index += consumed
        for word in leftover_words - counted_names:
            word_notice_counts[word] += 1
            if notice.protected:
                word_protected_counts[word] += 1

    ignored = {decision_key(decision.word) for decision in decisions.ignore}
    decided: dict[str, str] = {}
    for decision in decisions.alias_candidates:
        decided[decision_key(decision.word)] = "alias_candidates"
    for decision in decisions.new_entry_candidates:
        decided[decision_key(decision.word)] = "new_entry_candidates"

    shown = {
        name: tally
        for name, tally in candidate_tallies.items()
        if name not in ignored and (tally.notice_count >= min_count or name in decided)
    }
    ignored_candidate_count = sum(1 for name in candidate_tallies if name in ignored)
    below_min_count = len(candidate_tallies) - ignored_candidate_count - len(shown)

    alias_entry_ids = _alias_entry_ids(entries)
    alias_gap_candidates = []
    new_entry_candidates = []
    decided_candidates = []
    for name, tally in shown.items():
        candidate = GapCandidate(
            word=name,
            found_by=tally.found_by,
            protected_count=tally.protected_count,
            notice_count=tally.notice_count,
            surfaces=tally.top_surfaces(),
            examples=tally.examples(),
            similar_aliases=_similar_aliases(name, alias_entry_ids),
            related_words=tuple(sorted(other for other in shown if other != name and (other in name or name in other))),
            decision=decided.get(name),
        )
        if candidate.decision:
            decided_candidates.append(candidate)
        elif candidate.similar_aliases:
            alias_gap_candidates.append(candidate)
        else:
            new_entry_candidates.append(candidate)

    species_gaps = [
        SpeciesGap(
            alias=alias,
            species=species,
            other_species_entry_ids=tuple(sorted(species_entry_ids[(alias, species)])),
            protected_count=tally.protected_count,
            notice_count=tally.notice_count,
            surfaces=tally.top_surfaces(),
            examples=tally.examples(),
        )
        for (alias, species), tally in species_tallies.items()
    ]
    frequent_words = [
        FrequentWord(word, word_protected_counts[word], count)
        for word, count in word_notice_counts.items()
        if word not in ignored and count >= min_count
    ]
    frequent_words.sort(key=lambda item: (-item.protected_count, -item.notice_count, item.word))

    return GapReport(
        notice_count=notice_count,
        protected_count=protected_count,
        without_entry_count=without_entry_count,
        protected_without_entry_count=protected_without_entry_count,
        species_gaps=tuple(sorted(species_gaps, key=_order)),
        alias_gap_candidates=tuple(sorted(alias_gap_candidates, key=_order)),
        new_entry_candidates=tuple(sorted(new_entry_candidates, key=_order)),
        decided_candidates=tuple(sorted(decided_candidates, key=_order)),
        absent_decision_words=tuple(sorted(name for name in decided if name not in candidate_tallies)),
        frequent_words=tuple(frequent_words[:frequent_word_limit]),
        ignored_candidate_count=ignored_candidate_count,
        below_min_count=below_min_count,
        min_count=min_count,
    )


def render_markdown(report: GapReport) -> str:
    """사람이 읽는 후보 목록."""
    with_entry_count = report.notice_count - report.without_entry_count
    protected_with_entry_count = report.protected_count - report.protected_without_entry_count
    lines = [
        "# 건강 해설 빈틈 후보",
        "",
        "## 요약",
        "",
        f"- 읽은 공고: {report.notice_count}건 (보호중 {report.protected_count}건)",
        f"- 해설이 붙는 공고: {with_entry_count}건 (보호중 {protected_with_entry_count}건)",
        f"- 해설이 하나도 안 붙는 공고: {report.without_entry_count}건 (보호중 {report.protected_without_entry_count}건)",
        f"- 무시 목록으로 뺀 후보: {report.ignored_candidate_count}개",
        f"- 공고 {report.min_count}건 미만이라 뺀 후보: {report.below_min_count}개",
        "",
        "해설이 안 붙는 공고의 대부분은 성격, 외모만 적힌 공고다. 아래 후보는 자동으로 뽑은 것이라 병명이 아닌 낱말도 섞여 있다.",
        "",
        "## 그 종의 해설만 없는 것",
        "",
    ]
    if not report.species_gaps:
        lines.append("없음")
    for number, gap in enumerate(report.species_gaps, start=1):
        other_entries = ", ".join(gap.other_species_entry_ids)
        lines.append(
            f"{number}. **{gap.alias}** ({SPECIES_LABELS[gap.species]}) "
            f"보호중 {gap.protected_count}, 전체 {gap.notice_count}. 다른 종 해설: {other_entries}"
        )
        lines.extend(_detail_lines(gap.surfaces, gap.examples))
    lines.extend(["", "## 별칭만 없는 것일 수 있는 낱말 (비슷한 별칭 있음)", ""])
    lines.extend(_candidate_lines(report.alias_gap_candidates))
    lines.extend(["", "## 해설이 아예 없는 것일 수 있는 낱말 (비슷한 별칭 없음)", ""])
    lines.extend(_candidate_lines(report.new_entry_candidates))
    lines.extend(["", "## 이미 정한 것", ""])
    lines.extend(_candidate_lines(report.decided_candidates))
    if report.absent_decision_words:
        lines.extend(["", "정해 두었지만 이번에는 안 나온 낱말: " + ", ".join(report.absent_decision_words)])
    lines.extend(["", "## 후보로 안 잡힌 나머지 낱말 가운데 자주 나오는 것", ""])
    if not report.frequent_words:
        lines.append("없음")
    for word in report.frequent_words:
        lines.append(f"- {word.word}: 보호중 {word.protected_count}, 전체 {word.notice_count}")
    return "\n".join(lines) + "\n"


def _candidate_lines(candidates: tuple[GapCandidate, ...]) -> list[str]:
    if not candidates:
        return ["없음"]
    decision_labels = {"alias_candidates": "별칭 후보로 정함", "new_entry_candidates": "새 해설 후보로 정함"}
    lines = []
    for number, candidate in enumerate(candidates, start=1):
        headline = f"{number}. **{candidate.word}** 보호중 {candidate.protected_count}, 전체 {candidate.notice_count}"
        if candidate.decision:
            headline += f" ({decision_labels[candidate.decision]})"
        if candidate.found_by == "cue":
            headline += " (진단, 의심 같은 말의 앞 낱말)"
        lines.append(headline)
        if candidate.similar_aliases:
            similar = ", ".join(f"{item.alias}({', '.join(item.entry_ids)})" for item in candidate.similar_aliases)
            lines.append(f"   - 비슷한 별칭: {similar}")
        if candidate.related_words:
            lines.append(f"   - 관련 후보: {', '.join(candidate.related_words)}")
        lines.extend(_detail_lines(candidate.surfaces, candidate.examples))
    return lines


def _detail_lines(surfaces: tuple[tuple[str, int], ...], examples: tuple[GapExample, ...]) -> list[str]:
    lines = ["   - 적힌 꼴: " + ", ".join(f"{surface} {count}" for surface, count in surfaces)]
    for example in examples:
        status = "보호중" if example.protected else "보호 끝남"
        lines.append(f"   - 예시 [{example.notice_id}, {example.species}, {status}] {example.snippet}")
    return lines


def _order(item) -> tuple[int, int, str]:
    name = item.word if isinstance(item, GapCandidate) else item.alias
    return (-item.protected_count, -item.notice_count, name)


def _exclude_phrases(entries: list[HealthEntry]) -> list[str]:
    phrases = {normalize_text(phrase) for entry in entries for phrase in entry.match.exclude}
    return sorted(phrases, key=len, reverse=True)


def _alias_entry_ids(entries: list[HealthEntry]) -> dict[str, tuple[str, ...]]:
    alias_entry_ids: dict[str, list[str]] = {}
    for entry in entries:
        for alias in entry.match.aliases:
            alias_entry_ids.setdefault(normalize_text(alias), []).append(entry.id)
    return {alias: tuple(entry_ids) for alias, entry_ids in alias_entry_ids.items()}


def _similar_aliases(word: str, alias_entry_ids: dict[str, tuple[str, ...]]) -> tuple[SimilarAlias, ...]:
    close = difflib.get_close_matches(word, list(alias_entry_ids), n=MAX_SIMILAR_ALIASES, cutoff=SIMILAR_ALIAS_CUTOFF)
    return tuple(SimilarAlias(alias, alias_entry_ids[alias]) for alias in close)


def _mask(text: str, spans: list[tuple[int, int]], exclude_phrases: list[str]) -> str:
    """별칭 자리와 제외 구절('수유동' 같은 지명)을 지운다. 글 길이는 그대로라 자리 번호가 바뀌지 않는다."""
    characters = list(text)
    for start, end in spans:
        for index in range(start, end):
            characters[index] = MASK
    for phrase in exclude_phrases:
        for found in re.finditer(re.escape(phrase), text):
            for index in range(*found.span()):
                characters[index] = MASK
    return "".join(characters)


def _tokenize(masked: str) -> list[_Token]:
    found = list(TOKEN.finditer(masked))
    tokens = []
    for index, current in enumerate(found):
        joins_next = False
        if index + 1 < len(found):
            gap = masked[current.end():found[index + 1].start()]
            joins_next = JOIN_GAP.fullmatch(gap) is not None
        tokens.append(_Token(current.start(), current.end(), current.group(), joins_next))
    return tokens


def _reduce(word: str) -> tuple[str, str] | None:
    """낱말에서 조사와 상태 표현을 떼어 후보 이름을 만든다. 후보가 아니면 None.

    '자궁축농증의심' → 자궁축농증(끝 꼴), '악액질소견' → 악액질(단서 말 앞).
    """
    forms = [word]
    stripped_cue = False
    current = word
    for _ in range(MAX_STRIPS):
        ending = _trailing_word(current)
        if ending is None:
            break
        current = current[:-len(ending)]
        forms.append(current)
        if ending in CUE_WORDS:
            stripped_cue = True
    # 가장 많이 떼어 낸 꼴부터 본다('구내염증상있음'은 구내염증상이 아니라 구내염).
    for form in reversed(forms):
        if _is_disease_shaped(form):
            return form, "shape"
    if stripped_cue and _is_cue_name(forms[-1]):
        return forms[-1], "cue"
    return None


def _is_cue_name(name: str) -> bool:
    """단서 말 앞 낱말을 후보 이름으로 쓸 수 있는지."""
    if len(name) < MIN_WORD_LENGTH or not CUE_NAME.fullmatch(name):
        return False
    return name not in NOT_CUE_NAMES


def _trailing_word(word: str) -> str | None:
    """낱말 끝의 조사나 상태 표현. 떼고 나면 두 글자도 안 남는 것은 떼지 않는다."""
    for ending in TRAILING_WORDS:
        if word.endswith(ending) and len(word) - len(ending) >= MIN_WORD_LENGTH:
            return ending
    return None


def _strip_particles(word: str) -> str:
    for particle in sorted(PARTICLES, key=len, reverse=True):
        if word.endswith(particle) and len(word) - len(particle) >= MIN_WORD_LENGTH:
            return word[:-len(particle)]
    return word


def _is_disease_shaped(word: str) -> bool:
    # '3종 접종' 같은 숫자 낱말은 병명이 아니다.
    if len(word) < MIN_WORD_LENGTH or not HANGUL_WORD.fullmatch(word):
        return False
    if word.endswith(NOT_DISEASE_ENDINGS):
        return False
    for ending in DISEASE_ENDINGS:
        # 한 글자 끝 꼴과 '증상'은 앞에 다른 글자가 있어야 한다.
        needs_stem = len(ending) == 1 or ending == "증상"
        if word.endswith(ending) and (len(word) > len(ending) or not needs_stem):
            return True
    return False


def _whole_word(text: str, start: int, end: int) -> str:
    """별칭 자리를 품은 낱말 전체('마비' → '후지마비')."""
    while start > 0 and TOKEN.fullmatch(text[start - 1]):
        start -= 1
    while end < len(text) and TOKEN.fullmatch(text[end]):
        end += 1
    return text[start:end]


def _example(notice: Notice, text: str, start: int, end: int) -> GapExample:
    snippet_start = max(0, start - SNIPPET_MARGIN)
    snippet_end = min(len(text), end + SNIPPET_MARGIN)
    snippet = " ".join(text[snippet_start:snippet_end].split())
    if snippet_start > 0:
        snippet = "…" + snippet
    if snippet_end < len(text):
        snippet += "…"
    return GapExample(
        notice_id=notice.notice_id,
        species=SPECIES_LABELS[normalize_species(notice.species)],
        protected=notice.protected,
        snippet=snippet,
    )
