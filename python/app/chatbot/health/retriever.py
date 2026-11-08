"""건강 해설 검색기.

모든 검색기는 search(notice_text, question, species)로 해설 목록을 돌려준다.
이 모양을 지켜야 규칙, 벡터, 하이브리드 검색기를 바꿔 끼우고 입양 상담 에이전트의 도구로 쓸 수 있다.
"""

import re
import unicodedata
from dataclasses import dataclass
from typing import Protocol

from app.chatbot.health.knowledge import HealthEntry

MAX_RESULTS = 3

# 용어 뒤에서 문맥을 읽는 범위. 쉼표, 마침표 같은 구분자가 나오면 거기서 멈춘다.
CONTEXT_WINDOW = 14
SEPARATORS = re.compile(r"[,./;\n·]")

NEGATIVE_WORDS = re.compile(r"음성|\(-\)|negative")
POSITIVE_WORDS = re.compile(r"양성|\(\+\)|감염|확진|의심|positive")
# 예방약, 바르는 예방 처치도 접종처럼 "병이 있다"는 뜻이 아니다.
VACCINE_WORDS = re.compile(r"접종|백신|예방주사|예방약|도포")
# '외상없음'처럼 그 상태가 없다는 표현
ABSENT_WORDS = re.compile(r"없음|없슴|없다|없고|아님|이상 ?없")
ABSENT_WINDOW = 4

# 나열된 용어가 결과 하나를 함께 쓰는 경우('파보, 코로나 음성')에 문맥을 이어받는 최대 간격
LIST_GAP = re.compile(r"^[\s,&+/]{1,3}$")
HANGUL = re.compile(r"[가-힣]")


@dataclass
class _Hit:
    """글 안에서 찾은 별칭 한 자리."""

    start: int
    end: int
    alias: str
    context: str | None


@dataclass(frozen=True)
class HealthMatch:
    entry: HealthEntry
    score: float
    reason: str


class HealthRetriever(Protocol):
    def search(self, notice_text: str | None, question: str | None, species: str | None) -> list[HealthMatch]:
        ...


def normalize_text(text: str | None) -> str:
    """전각 문자를 바꾸고 소문자로 맞춘다. 띄어쓰기는 문맥 판단에 쓰므로 남긴다."""
    if not text:
        return ""
    return unicodedata.normalize("NFKC", text).lower()


OTHER_SPECIES = "other"


def normalize_species(species: str | None) -> str | None:
    """dog, cat, 그 밖의 동물(other), 모르면 None."""
    if not species:
        return None
    value = species.strip().lower()
    if value in {"dog", "개", "강아지"}:
        return "dog"
    if value in {"cat", "고양이"}:
        return "cat"
    if value in {"etc", "기타"}:
        # 거북이, 토끼 같은 동물에는 개, 고양이 해설을 붙이지 않는다.
        return OTHER_SPECIES
    return None


def detect_context(text: str, start: int, end: int) -> str | None:
    """용어가 나온 자리의 문맥을 읽는다: vaccination, absent, negative, positive, 모르면 None."""
    # '종합백신'처럼 용어 자체가 백신 이름이거나 접종 괄호 안이면 접종 표기다.
    if VACCINE_WORDS.search(text[start:end]) or _inside_vaccine_list(text, start):
        return "vaccination"

    after = text[end:end + CONTEXT_WINDOW]
    separator = SEPARATORS.search(after)
    if separator:
        after = after[:separator.start()]
    before = text[max(0, start - 4):start]

    if VACCINE_WORDS.search(after) or VACCINE_WORDS.search(before):
        return "vaccination"
    if NEGATIVE_WORDS.search(after):
        return "negative"
    # '외상없음', '피부병 없음'처럼 바로 뒤에 붙을 때만 부정으로 본다.
    # '허피스로 기력이 없고'의 '없고'는 다른 말(기력)에 대한 것이다.
    if ABSENT_WORDS.search(after[:ABSENT_WINDOW]):
        return "absent"
    if POSITIVE_WORDS.search(after):
        return "positive"
    return None


def _stands_alone(text: str, start: int, end: int) -> bool:
    """한 글자 별칭('옴')이 '나옴'처럼 다른 낱말의 일부가 아닌지 본다."""
    before = text[start - 1] if start > 0 else ""
    after = text[end] if end < len(text) else ""
    return not HANGUL.match(before) and not HANGUL.match(after)


def _inherit_list_context(text: str, hits: list[_Hit], test_aliases: set[str]) -> None:
    """'파보, 코로나 음성'처럼 나열된 검사 용어는 바로 뒤 검사 용어의 결과를 함께 쓴다.

    '유선종양, 심장사상충 음성'처럼 검사 용어가 아닌 것은 이어받지 않는다.
    """
    for index in range(len(hits) - 2, -1, -1):
        current = hits[index]
        following = hits[index + 1]
        if current.context is not None or following.context not in {"negative", "positive"}:
            continue
        if current.alias not in test_aliases or following.alias not in test_aliases:
            continue
        gap = text[current.end:following.start]
        if LIST_GAP.match(gap):
            current.context = following.context


def _inside_vaccine_list(text: str, position: int) -> bool:
    """'접종(종합백신/코로나장염/광견병)'처럼 접종 괄호 안에 있는지 본다."""
    open_index = text.rfind("(", 0, position)
    if open_index < 0:
        return False
    close_index = text.find(")", open_index)
    if 0 <= close_index < position:
        return False
    prefix = text[max(0, open_index - 6):open_index]
    return VACCINE_WORDS.search(prefix) is not None


class RuleHealthRetriever:
    """별칭 사전과 문맥 규칙으로 해설을 찾는다."""

    def __init__(self, entries: list[HealthEntry]):
        self._alias_map: dict[str, list[HealthEntry]] = {}
        for entry in entries:
            for alias in entry.match.aliases:
                self._alias_map.setdefault(normalize_text(alias), []).append(entry)
        # 긴 별칭을 먼저 찾아 '심장사상충' 안의 '사상충'을 두 번 세지 않는다.
        self._aliases_longest_first = sorted(self._alias_map, key=len, reverse=True)
        # 별칭이 다른 낱말(지명 등)의 일부로 나오는 표현. 어느 해설에 적혀 있든
        # 이 표현이 차지한 자리에서는 아무 해설도 찾지 않는다.
        self._excludes = sorted(
            {normalize_text(phrase) for entry in entries for phrase in entry.match.exclude},
            key=len,
            reverse=True,
        )
        # 양성과 음성 해설이 나뉘는 검사 용어(사상충, 파보 등)
        self._test_aliases = {
            alias
            for alias, alias_entries in self._alias_map.items()
            if {entry.match.context for entry in alias_entries} & {"positive", "negative"}
        }

    def search(self, notice_text: str | None, question: str | None, species: str | None) -> list[HealthMatch]:
        wanted_species = normalize_species(species)
        notice_hits = self._find(normalize_text(notice_text))
        question_hits = self._find(normalize_text(question))

        results: dict[str, HealthMatch] = {}
        # 사용자가 직접 물은 용어를 먼저 둔다. 문맥은 질문에 없으면 공고에서 가져온다.
        notice_aliases = {alias for alias, _context in notice_hits}
        for alias, context in question_hits:
            notice_context = self._context_for_alias(alias, notice_hits)
            used_context = context or notice_context
            # 공고에 없는 병 이름을 물었으면 문맥이 없어도 병 해설로 답한다.
            # 공고에 결과 없이 이름만 있으면('홍역키트 검사') 양성으로 짐작하지 않는다.
            fallback = alias not in notice_aliases
            for chosen in self._choose_entries(alias, used_context, wanted_species, allow_positive_fallback=fallback):
                reason = f"질문의 '{alias}'({used_context or '문맥 없음'})"
                results.setdefault(chosen.id, HealthMatch(chosen, 1.0, reason))
        for alias, context in notice_hits:
            # 공고에 결과 없이 이름만 있으면('파보 키트 검사') 양성으로 짐작하지 않는다.
            for chosen in self._choose_entries(alias, context, wanted_species, allow_positive_fallback=False):
                reason = f"공고의 '{alias}'({context or '문맥 없음'})"
                results.setdefault(chosen.id, HealthMatch(chosen, 0.9, reason))
        return list(results.values())[:MAX_RESULTS]

    def blocked_entry_ids(self, notice_text: str | None, question: str | None, species: str | None) -> set[str]:
        """'피부병 없음', '사상충 음성', '접종(코로나장염)'처럼 규칙이 병 해설을 막은 자리의 해설 id.

        하이브리드 검색에서 벡터가 이 해설을 다시 넣지 못하게 한다.
        """
        wanted_species = normalize_species(species)
        blocked = set()
        for text in (notice_text, question):
            for alias, context in self._find(normalize_text(text)):
                if context not in {"absent", "negative", "vaccination"}:
                    continue
                for entry in self._entries_for(alias):
                    if wanted_species and wanted_species not in entry.species:
                        continue
                    if entry.match.context != context:
                        blocked.add(entry.id)
        return blocked

    def _find(self, text: str) -> list[tuple[str, str | None]]:
        """글 속의 별칭과 그 자리의 문맥을 앞에서부터 순서대로 돌려준다. 이미 찾은 자리는 다시 쓰지 않는다."""
        hits: list[_Hit] = []
        taken = [False] * len(text)
        for phrase in self._excludes:
            for found in re.finditer(re.escape(phrase), text):
                for index in range(*found.span()):
                    taken[index] = True
        for alias in self._aliases_longest_first:
            for found in re.finditer(re.escape(alias), text):
                start, end = found.span()
                if any(taken[start:end]):
                    continue
                if len(alias) == 1 and not _stands_alone(text, start, end):
                    continue
                for index in range(start, end):
                    taken[index] = True
                hits.append(_Hit(start, end, alias, detect_context(text, start, end)))
        hits.sort(key=lambda hit: hit.start)
        _inherit_list_context(text, hits, self._test_aliases)
        return [(hit.alias, hit.context) for hit in hits]

    @staticmethod
    def _context_for_alias(alias: str, notice_hits: list[tuple[str, str | None]]) -> str | None:
        for notice_alias, context in notice_hits:
            if notice_alias == alias and context:
                return context
        return None

    def _choose_entries(
        self,
        alias: str,
        context: str | None,
        species: str | None,
        allow_positive_fallback: bool,
    ) -> list[HealthEntry]:
        """별칭과 문맥에 맞는 해설을 고른다. 같은 별칭에 같은 문맥의 해설이 여럿이면 모두 돌려준다."""
        if context == "absent":
            return []
        candidates = [entry for entry in self._entries_for(alias) if species is None or species in entry.species]
        by_context: dict[str, list[HealthEntry]] = {}
        for entry in candidates:
            by_context.setdefault(entry.match.context, []).append(entry)
        if context in by_context:
            return by_context[context]
        # 문맥이 접종이나 음성인데 맞는 해설이 없으면 병 해설을 붙이지 않는다.
        if context in {"negative", "vaccination"}:
            return []
        if "any" in by_context:
            return by_context["any"]
        if context == "positive" or allow_positive_fallback:
            return by_context.get("positive", [])
        return []

    def _entries_for(self, alias: str) -> list[HealthEntry]:
        return self._alias_map.get(alias, [])
