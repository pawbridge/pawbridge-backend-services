"""입양 안내 도구. 공식 안내(법제처 생활법령, 정부24, 국가동물보호정보시스템, 농림축산식품부)를
주제별로 옮겨 둔 자료에서 모델이 고른 주제를 돌려준다. 검색이 아니라 주제 목록 조회다(자료가 6개 주제뿐이다).

자료 원문은 adoption_guides.json이고, 문장마다 출처를 단다. 입양 희망자에게 사실로 나가는 내용이라
자료를 고칠 때는 출처 원문과 대조하고 사람이 검수한다.
"""

import json
from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field

GUIDE_PATH = Path(__file__).with_name("adoption_guides.json")

# 모델이 고르는 주제 이름. 자료 파일의 topics[].id와 같아야 한다(시험이 확인한다).
AdoptionTopic = Literal["eligibility", "checklist", "cost-support", "adopter-duties", "registration", "trial-adoption"]

CLOSING = "보호소마다 절차와 자격이 다를 수 있으니, 이 보호소에 전화로 확인하라고 함께 안내하세요."


class AdoptionGuideArgs(BaseModel):
    topic: AdoptionTopic = Field(
        description=(
            "eligibility(보호소 입양 자격, 공고 기간과 소유권), checklist(입양 전 확인할 것), "
            "cost-support(입양비 지원 금액, 대상, 서류, 신청 기한), adopter-duties(입양자 준수사항, 입양 전 교육), "
            "registration(동물등록 의무와 신고), trial-adoption(예비입양제)"
        ),
    )


@lru_cache(maxsize=1)
def load_guides() -> dict:
    with GUIDE_PATH.open(encoding="utf-8") as handle:
        return json.load(handle)


def adoption_guide_text(topic: str) -> str:
    """주제 하나의 안내 문장과 출처를 프롬프트에 넣을 글로 만든다."""
    guides = load_guides()
    chosen = next((item for item in guides["topics"] if item["id"] == topic), None)
    if chosen is None:
        return "그런 입양 안내 주제가 없습니다. 보호소에 확인하라고 안내하세요."
    lines = [f"[입양 안내: {chosen['title']}]"]
    used_sources = []
    for fact in chosen["facts"]:
        lines.append(f"- {fact['text']}")
        for name in fact["sources"]:
            if name not in used_sources:
                used_sources.append(name)
    for name in used_sources:
        source = guides["sources"][name]
        lines.append(f"(출처: {source['publisher']} 「{source['title']}」, {source['basis']})")
    lines.append(CLOSING)
    return "\n".join(lines)
