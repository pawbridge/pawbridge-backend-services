"""검수된 건강 해설을 파일에서 읽고 검사한다.

해설 하나는 JSON 파일 하나다. 서비스가 시작할 때 모두 읽고,
형식이 틀리거나 금지 표현이 있으면 바로 실패시켜 잘못된 해설이 운영에 나가지 않게 한다.
"""

import json
import re
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator

DEFAULT_ENTRIES_DIR = Path(__file__).resolve().parent / "entries"

Species = Literal["dog", "cat"]
MatchContext = Literal["positive", "negative", "any", "vaccination"]

# 진단, 예후, 처방을 단정하는 표현. 해설 문장에 있으면 안 된다.
FORBIDDEN_PATTERNS = [
    re.compile(r"완치"),
    re.compile(r"괜찮(습니다|아요|다)"),
    re.compile(r"문제(가)? 없(습니다|어요|다)"),
    re.compile(r"걱정(하지)? ?(마세요|없)"),
    re.compile(r"(먹이|투여하|바르)세요"),
    re.compile(r"\d+\s?(mg|ml|㎎|㎖)"),
]


class HealthSource(BaseModel):
    org: str
    title: str
    url: str
    tier: Literal["A", "B"]
    language: str
    checked_at: str


class HealthFact(BaseModel):
    text: str
    sources: list[str] = Field(min_length=1)
    # 개나 고양이에만 해당하는 문장이면 적는다. 비어 있으면 해설의 모든 종에 해당한다.
    species: list[Species] = Field(default_factory=list)

    @field_validator("text")
    @classmethod
    def reject_forbidden_phrases(cls, value: str) -> str:
        for pattern in FORBIDDEN_PATTERNS:
            if pattern.search(value):
                raise ValueError(f"금지 표현이 있습니다: {pattern.pattern}")
        return value


class HealthMatchRule(BaseModel):
    aliases: list[str] = Field(min_length=1)
    context: MatchContext
    # 별칭이 이 표현 안에 있으면 무시한다(예: '수유' 해설에 지명 '수유동').
    # 검색기는 모든 해설의 무시 표현을 함께 적용한다. 그 자리에서는 어떤 해설도 찾지 않는다.
    exclude: list[str] = Field(default_factory=list)


class HealthReview(BaseModel):
    status: Literal["draft", "approved"]
    notes: str = ""


class HealthEntry(BaseModel):
    id: str
    title: str
    species: list[Species] = Field(min_length=1)
    match: HealthMatchRule
    summary: list[HealthFact] = Field(min_length=1)
    cautions: list[HealthFact] = Field(min_length=1)
    ask_shelter: list[str] = Field(default_factory=list)
    sources: dict[str, HealthSource] = Field(min_length=1)
    review: HealthReview

    def facts_for(self, species: str | None) -> tuple[list[HealthFact], list[HealthFact]]:
        """그 동물에 맞는 요약과 주의 문장만 돌려준다(답변 생성에 넘길 근거)."""

        def keep(fact: HealthFact) -> bool:
            return not species or not fact.species or species in fact.species

        return [fact for fact in self.summary if keep(fact)], [fact for fact in self.cautions if keep(fact)]

    @model_validator(mode="after")
    def check_source_references(self):
        for fact in [*self.summary, *self.cautions]:
            missing = [name for name in fact.sources if name not in self.sources]
            if missing:
                raise ValueError(f"{self.id}: 정의되지 않은 출처 {missing}")
            outside = [name for name in fact.species if name not in self.species]
            if outside:
                raise ValueError(f"{self.id}: 해설에 없는 종이 문장에 있습니다 {outside}")
        if not any(source.tier == "A" for source in self.sources.values()):
            raise ValueError(f"{self.id}: A급 출처가 하나 이상 필요합니다")
        return self


def load_entries(entries_dir: Path = DEFAULT_ENTRIES_DIR, include_drafts: bool = False) -> list[HealthEntry]:
    """해설 파일을 모두 읽는다. 운영은 승인된 해설만 쓴다(include_drafts=False)."""
    entries = []
    for path in sorted(entries_dir.glob("*.json")):
        entry = HealthEntry.model_validate(json.loads(path.read_text(encoding="utf-8")))
        if entry.id != path.stem:
            raise ValueError(f"{path.name}: id와 파일 이름이 다릅니다")
        if entry.review.status == "approved" or include_drafts:
            entries.append(entry)
    return entries
