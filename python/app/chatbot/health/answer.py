"""검수된 해설을 근거로 답변을 만들고 검사한다(RAG의 생성 단계).

흐름: 찾은 해설 → 근거 묶기(번호) → 프롬프트 → LLM → 답 검사 → 출처 조립.
LLM에는 번호와 문장만 넘기고, 출처 이름과 링크는 코드가 해설 파일에서 붙인다.
"""

import json
import re
from dataclasses import dataclass, field
from typing import Literal

from app.chatbot.health.knowledge import HealthEntry, HealthFact
from app.chatbot.health.retriever import HealthMatch, normalize_species

Section = Literal["summary", "cautions"]

# Gemini는 근거 번호를 [1]뿐 아니라 [1, 3]처럼 묶어 쓰기도 한다.
CITATION = re.compile(r"\[(\d+(?:\s*,\s*\d+)*)\]")
# 줄바꿈은 남기려고 번호 앞의 공백과 탭만 같이 지운다.
CITATION_WITH_SPACE = re.compile(r"[ \t]*\[\d+(?:\s*,\s*\d+)*\]")
JSON_OBJECT = re.compile(r"\{.*\}", re.DOTALL)
SENTENCE_END = re.compile(r"(?<=[.!?。])\s+")
# 백엔드가 답을 저장하는 chatbot_messages.content가 1000자라 그 아래로 자른다.
MAX_ANSWER_CHARS = 900

# 화면에는 출처와 해설 칸이 없으므로 대체 답변은 그 칸을 가리키지 않는다.
# '검수된'은 수의사 검수로 읽힐 수 있어 쓰지 않는다.
FALLBACK_ANSWER = (
    "이 질문에는 지금 자세히 답하기 어렵습니다. "
    "공고에 적힌 상태와 치료 여부는 보호소에, 정확한 건강 상태는 동물병원 진료로 확인해 주세요."
)
NO_EVIDENCE_FALLBACK = (
    "이 질문에 맞는 건강 정보가 없어 자세히 답하기 어렵습니다. "
    "공고에 적힌 상태와 치료 여부는 보호소에 문의하고, 정확한 건강 상태는 동물병원 진료로 확인해 주세요."
)

# 답변에서 막는 표현 두 묶음.
# 의학 표현은 근거가 있든 없든 건강 해설 RAG가 켜진 답변 전체에서 막는다.
MEDICAL_FORBIDDEN = [
    re.compile(r"완치"),
    re.compile(r"(투여|처방)(하|받으)?세요"),
    re.compile(r"(바르|발라)\s?(주)?세요"),
    re.compile(r"약[을를]?\s?(먹이|먹여)"),
    re.compile(r"\d+\s?(mg|ml|㎎|㎖|밀리그램|밀리리터)"),
    re.compile(r"회복(됩니다|될 거|할 거)"),
    re.compile(r"안심(하셔도|해도|하세요)"),
]
# 안심시키는 일상 표현은 근거 번호가 붙은 문장(건강 설명)에서만 막는다.
# "아파트에서 키워도 괜찮습니다"처럼 건강과 무관한 문장까지 막지 않기 위해서다.
REASSURANCE_FORBIDDEN = [
    re.compile(r"괜찮(습니다|아요|다|을 거)"),
    re.compile(r"문제\s?(가|는)?\s?없"),
    re.compile(r"걱정(하지)?\s?(마세요|않으셔도|없)"),
    re.compile(r"(먹이|먹여)\s?(주)?세요"),
]

HEALTH_RULES = [
    "아래 [검수된 건강 근거]는 출처가 확인된 일반 정보입니다.",
    "건강에 관한 사실은 이 근거에 있는 것만 말하세요. 근거에 없는 병명, 약, 용량, 수치, 예후는 말하지 마세요.",
    "근거를 쓴 문장 끝에 그 근거 번호를 [1]처럼 붙이세요.",
    "질문과 관련 없는 근거는 쓰지 마세요.",
    "근거로 답할 수 없으면 모른다고 하고 보호소나 동물병원 확인을 권하세요.",
    "'공고에는 없는 질문 관련 일반 정보'라고 표시된 근거는 이 동물이 그 병이 있다는 뜻이 아니니, 일반 정보로만 말하세요.",
    '다음 JSON 하나로만 답하세요: {"answer": "답변", "used": [쓴 근거 번호]}',
]


# 건강 해설을 찾지 못했을 때(RAG는 돌았지만 근거가 없음) 넣는 규칙
NO_EVIDENCE_RULES = [
    "이 질문에 맞는 검수된 건강 근거를 찾지 못했습니다.",
    "병명, 증상의 원인, 치료 방법, 약, 수치, 회복 가능성 같은 구체적인 의학 정보는 말하지 마세요.",
    "건강에 관한 질문이면 공고에 적힌 내용만 그대로 전하고, 보호소에 물어볼 것과 동물병원 진료가 필요하다는 점을 안내하세요.",
    "건강과 관계없는 질문(입양 절차, 성격, 준비물 등)은 평소처럼 답하세요.",
]


@dataclass(frozen=True)
class Evidence:
    number: int
    entry: HealthEntry
    fact: HealthFact
    section: Section
    basis: str = "notice"


@dataclass
class CheckedAnswer:
    answer: str
    used: list[int]
    problems: list[str] = field(default_factory=list)
    fallback: bool = False


def build_evidence(
    matches: list[HealthMatch],
    species: str | None,
    max_summary_per_entry: int | None = None,
) -> list[Evidence]:
    """찾은 해설에서 그 동물에 맞는 문장만 골라 번호를 붙인다.

    주의 문장은 모두 넣고, 요약 문장은 해설마다 max_summary_per_entry개까지 넣는다.
    """
    wanted_species = normalize_species(species)
    evidence: list[Evidence] = []
    for match in matches:
        summary, cautions = match.entry.facts_for(wanted_species)
        if max_summary_per_entry is not None:
            summary = summary[:max_summary_per_entry]
        for section, facts in (("summary", summary), ("cautions", cautions)):
            for fact in facts:
                evidence.append(Evidence(len(evidence) + 1, match.entry, fact, section, match.basis))
    return evidence


def format_evidence(evidence: list[Evidence]) -> str:
    """프롬프트에 넣을 근거 목록. 해설 제목을 붙여 어느 병 이야기인지 남긴다.

    질문에만 나온 용어로 찾은 해설은 이 동물의 상태가 아니라는 표시를 붙인다.
    """
    lines = []
    for item in evidence:
        label = item.entry.title if item.basis == "notice" else f"{item.entry.title}, 이 동물의 공고에는 없는 질문 관련 일반 정보"
        lines.append(f"[{item.number}] ({label}) {item.fact.text}")
    return "\n".join(lines)


def check_answer(raw: str, evidence: list[Evidence], expect_json: bool = False) -> CheckedAnswer:
    """근거를 준 뒤 받은 LLM 응답을 검사한다. 고칠 수 없는 문제가 있으면 대체 답변으로 바꾼다.

    expect_json이 참이면(JSON으로 답하라고 요청한 경우) JSON이 아닌 응답은 모두 대체 답변으로 바꾼다.
    거짓이면(JSON을 못 지키는 모델) 평범한 글로 보고 본문의 [번호]로 검사한다.
    """
    known = {item.number for item in evidence}
    problems: list[str] = []

    parsed = _parse_json(raw)
    if parsed is BROKEN_JSON or (expect_json and parsed is None):
        return CheckedAnswer(FALLBACK_ANSWER, [], ["JSON 응답을 해석할 수 없음"], fallback=True)
    if parsed is None:
        # JSON을 따르지 않은 모델(로컬 모델 등)도 본문의 [번호]로 검사할 수 있다.
        problems.append("JSON 형식 아님, 본문 인용으로 처리")
        answer = raw.strip()
        used = []
    else:
        answer_value = parsed.get("answer")
        if not isinstance(answer_value, str):
            return CheckedAnswer(FALLBACK_ANSWER, [], ["answer가 글이 아님"], fallback=True)
        answer = answer_value.strip()
        used = _as_numbers(parsed.get("used"))
    if not answer:
        return CheckedAnswer(FALLBACK_ANSWER, [], problems + ["답변 비어 있음"], fallback=True)

    unknown = sorted({number for number in used if number not in known})
    if unknown:
        problems.append(f"없는 근거 번호 {unknown}")
        return CheckedAnswer(FALLBACK_ANSWER, [], problems, fallback=True)

    # 길이를 먼저 자르고 나서 번호를 모아야, 잘려 나간 문장의 근거가 출처에 남지 않는다.
    if len(answer) > MAX_ANSWER_CHARS:
        answer = answer[:MAX_ANSWER_CHARS].rstrip()
        answer = re.sub(r"\s?\[\d*$", "", answer) + "…"
        problems.append("길이 상한으로 자름")

    forbidden = _forbidden_in(answer)
    if forbidden:
        return CheckedAnswer(FALLBACK_ANSWER, [], problems + forbidden, fallback=True)

    # 본문에 붙은 번호는 실제 있는 근거만 남기고, used와 합친다. [1, 9]처럼 묶인 표기는 있는 번호만 남긴다.
    cited = []

    def keep_known_numbers(found: re.Match) -> str:
        numbers = [int(text) for text in re.split(r"\s*,\s*", found.group(1))]
        kept = [number for number in numbers if number in known]
        for number in numbers:
            if number not in known:
                problems.append(f"본문의 없는 번호 [{number}] 지움")
        cited.extend(kept)
        return f"[{', '.join(str(number) for number in kept)}]" if kept else ""

    answer = CITATION.sub(keep_known_numbers, answer)
    used = sorted(set(used) | set(cited))
    return CheckedAnswer(answer, used, problems)


def strip_citations(answer: str) -> str:
    """사용자에게 보낼 답에서 근거 번호 [n]을 지운다.

    번호는 답 검사와 로그(쓴 근거)에만 쓰고 화면에는 보이지 않는다. 번호 앞 공백도 같이 지워
    "제한해야 합니다 [2]."가 "제한해야 합니다."가 되게 한다.
    """
    return CITATION_WITH_SPACE.sub("", answer).strip()


def check_plain_answer(raw: str) -> CheckedAnswer:
    """근거를 찾지 못한 채 받은 답을 검사한다. 의학 표현이 있으면 근거 없는 안내로 바꾼다."""
    answer = raw.strip()
    if not answer:
        return CheckedAnswer(NO_EVIDENCE_FALLBACK, [], ["답변 비어 있음"], fallback=True)
    problems = [f"금지 표현 '{pattern.pattern}'" for pattern in MEDICAL_FORBIDDEN if pattern.search(answer)]
    if problems:
        return CheckedAnswer(NO_EVIDENCE_FALLBACK, [], problems, fallback=True)
    if len(answer) > MAX_ANSWER_CHARS:
        answer = answer[:MAX_ANSWER_CHARS].rstrip() + "…"
        problems.append("길이 상한으로 자름")
    return CheckedAnswer(answer, [], problems)


def _forbidden_in(answer: str) -> list[str]:
    """의학 표현은 답 전체에서, 안심 표현은 근거 번호가 붙은 문장에서만 찾는다."""
    problems = [f"금지 표현 '{pattern.pattern}'" for pattern in MEDICAL_FORBIDDEN if pattern.search(answer)]
    cited_sentences = [sentence for sentence in SENTENCE_END.split(answer) if CITATION.search(sentence)]
    for sentence in cited_sentences:
        for pattern in REASSURANCE_FORBIDDEN:
            if pattern.search(sentence):
                problems.append(f"근거 문장의 안심 표현 '{pattern.pattern}'")
    return problems


def sources_for(used: list[int], evidence: list[Evidence]) -> list[dict]:
    """쓴 근거 번호의 출처를 해설 파일에서 꺼낸다(LLM이 쓴 링크는 쓰지 않는다)."""
    by_number = {item.number: item for item in evidence}
    sources: list[dict] = []
    seen_urls: set[str] = set()
    for number in used:
        item = by_number[number]
        for name in item.fact.sources:
            source = item.entry.sources[name]
            if source.url in seen_urls:
                continue
            seen_urls.add(source.url)
            sources.append(
                {
                    "org": source.org,
                    "title": source.title,
                    "url": source.url,
                    "checkedAt": source.checked_at,
                    "language": source.language,
                }
            )
    return sources


def health_cards(matches: list[HealthMatch], species: str | None) -> list[dict]:
    """'검수된 해설 보기'에 보여 줄 사람이 검수한 원문(그 동물에 맞는 문장만)."""
    wanted_species = normalize_species(species)
    cards = []
    for match in matches:
        summary, cautions = match.entry.facts_for(wanted_species)
        cards.append(
            {
                "id": match.entry.id,
                "title": match.entry.title,
                "summary": [fact.text for fact in summary],
                "cautions": [fact.text for fact in cautions],
                "askShelter": list(match.entry.ask_shelter),
                # notice: 공고에 적힌 이 동물의 상태, question: 질문에 대한 일반 정보(화면에서 구분해 표시)
                "basis": match.basis,
            }
        )
    return cards


# JSON처럼 시작하는데 해석이 안 되는 응답. 원문을 사용자에게 내보내면 안 된다.
BROKEN_JSON = object()


def _parse_json(raw: str):
    """JSON 객체면 dict, 평범한 글이면 None, JSON처럼 시작하는데 깨졌으면 BROKEN_JSON."""
    text = raw.strip()
    looks_like_json = text.startswith(("{", "[", "```"))
    found = JSON_OBJECT.search(text)
    if not found:
        return BROKEN_JSON if looks_like_json else None
    try:
        parsed = json.loads(found.group(0))
    except json.JSONDecodeError:
        return BROKEN_JSON if looks_like_json else None
    if not isinstance(parsed, dict):
        return BROKEN_JSON if looks_like_json else None
    return parsed


def _as_numbers(value) -> list[int]:
    if not isinstance(value, list):
        return []
    numbers = []
    for item in value:
        try:
            numbers.append(int(item))
        except (TypeError, ValueError):
            continue
    return numbers
