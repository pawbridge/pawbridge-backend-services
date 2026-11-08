"""검수된 해설을 근거로 답변을 만들고 검사한다(RAG의 생성 단계).

흐름: 찾은 해설 → 근거 묶기(번호) → 프롬프트 → LLM → 답 검사 → 출처 조립.
LLM에는 번호와 문장만 넘기고, 출처 이름과 링크는 코드가 해설 파일에서 붙인다.
"""

import json
import re
from dataclasses import dataclass, field
from typing import Literal

from app.chatbot.health.knowledge import FORBIDDEN_PATTERNS, HealthEntry, HealthFact
from app.chatbot.health.retriever import HealthMatch, normalize_species

Section = Literal["summary", "cautions"]

CITATION = re.compile(r"\[(\d+)\]")
JSON_OBJECT = re.compile(r"\{.*\}", re.DOTALL)
MAX_ANSWER_CHARS = 1200

FALLBACK_ANSWER = (
    "공고에 적힌 건강 상태에 대한 검수된 해설을 아래에서 확인해 주세요. "
    "이 동물의 실제 상태와 치료 여부는 보호소와 동물병원에 확인이 필요합니다."
)

HEALTH_RULES = [
    "아래 [검수된 건강 근거]는 출처가 확인된 일반 정보입니다.",
    "건강에 관한 사실은 이 근거에 있는 것만 말하세요. 근거에 없는 병명, 약, 용량, 수치, 예후는 말하지 마세요.",
    "근거를 쓴 문장 끝에 그 근거 번호를 [1]처럼 붙이세요.",
    "질문과 관련 없는 근거는 쓰지 마세요.",
    "근거로 답할 수 없으면 모른다고 하고 보호소나 동물병원 확인을 권하세요.",
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
                evidence.append(Evidence(len(evidence) + 1, match.entry, fact, section))
    return evidence


def format_evidence(evidence: list[Evidence]) -> str:
    """프롬프트에 넣을 근거 목록. 해설 제목을 붙여 어느 병 이야기인지 남긴다."""
    lines = [f"[{item.number}] ({item.entry.title}) {item.fact.text}" for item in evidence]
    return "\n".join(lines)


def check_answer(raw: str, evidence: list[Evidence]) -> CheckedAnswer:
    """LLM 응답을 검사한다. 고칠 수 없는 문제가 있으면 대체 답변으로 바꾼다."""
    known = {item.number for item in evidence}
    problems: list[str] = []

    parsed = _parse_json(raw)
    if parsed is None:
        # JSON을 따르지 않은 모델(로컬 모델 등)도 본문의 [번호]로 검사할 수 있다.
        problems.append("JSON 형식 아님, 본문 인용으로 처리")
        answer = raw.strip()
        used = []
    else:
        answer = str(parsed.get("answer") or "").strip()
        used = _as_numbers(parsed.get("used"))
    if not answer:
        return CheckedAnswer(FALLBACK_ANSWER, [], problems + ["답변 비어 있음"], fallback=True)

    unknown = sorted({number for number in used if number not in known})
    if unknown:
        problems.append(f"없는 근거 번호 {unknown}")
        return CheckedAnswer(FALLBACK_ANSWER, [], problems, fallback=True)

    forbidden = [f"금지 표현 '{pattern.pattern}'" for pattern in FORBIDDEN_PATTERNS if pattern.search(answer)]
    if forbidden:
        return CheckedAnswer(FALLBACK_ANSWER, [], problems + forbidden, fallback=True)

    # 본문에 붙은 번호는 실제 있는 근거만 남기고, used와 합친다.
    cited = []
    for number_text in CITATION.findall(answer):
        number = int(number_text)
        if number in known:
            cited.append(number)
        else:
            problems.append(f"본문의 없는 번호 [{number}] 지움")
    answer = CITATION.sub(lambda found: found.group(0) if int(found.group(1)) in known else "", answer)
    used = sorted(set(used) | set(cited))

    if len(answer) > MAX_ANSWER_CHARS:
        answer = answer[:MAX_ANSWER_CHARS].rstrip() + "…"
        problems.append("길이 상한으로 자름")
    return CheckedAnswer(answer, used, problems)


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
            }
        )
    return cards


def _parse_json(raw: str) -> dict | None:
    text = raw.strip()
    found = JSON_OBJECT.search(text)
    if not found:
        return None
    try:
        parsed = json.loads(found.group(0))
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


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
