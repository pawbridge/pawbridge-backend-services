"""에이전트 도구. 프레임워크 없는 평범한 함수와 pydantic 입력 모델이다.

모델이 정하는 것은 도구 입력(검색어)뿐이다. 공고, 종처럼 요청에서 정해지는 값은 코드가 넣는다.
도구를 더하려면 입력 모델, 함수, ToolSpec을 만들고 TOOLS에 넣는다(프롬프트의 도구 설명도 한 줄).
"""

from dataclasses import dataclass, field

from pydantic import BaseModel, Field

from app.chatbot.agent.adoption import AdoptionGuideArgs, adoption_guide_text
from app.chatbot.health.answer import Evidence, build_evidence, format_evidence
from app.chatbot.health.retriever import HealthMatch, HealthRetriever


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    args_schema: type[BaseModel]


@dataclass
class ToolContext:
    """요청마다 코드가 정하는 값과 지금까지 모은 근거. 도구가 근거를 더하면 여기에 쌓인다."""

    retriever: HealthRetriever
    notice_text: str | None
    species: str | None
    matches: list[HealthMatch] = field(default_factory=list)
    evidence: list[Evidence] = field(default_factory=list)


class SearchHealthArgs(BaseModel):
    query: str = Field(
        min_length=1,
        max_length=40,
        description="병명, 증상, 검사 이름 같은 짧은 낱말. 예: 심장사상충, 기침, 피부병",
    )


SEARCH_HEALTH = ToolSpec(
    name="search_health",
    description="보호동물 건강 해설(출처가 확인된 일반 정보)을 병명이나 증상 낱말로 찾는다. 찾은 근거를 번호와 함께 돌려준다.",
    args_schema=SearchHealthArgs,
)

NOTHING_FOUND = "새로 찾은 건강 해설이 없습니다. 이미 받은 근거로 답하거나, 근거가 없으면 보호소와 동물병원 확인을 안내하세요."


def search_health(args: SearchHealthArgs, context: ToolContext) -> str:
    """검색어로 해설을 더 찾아 근거에 잇는다. 이미 받은 해설은 다시 넣지 않는다.

    공고 특이사항을 함께 넘겨야 '사상충'처럼 공고에 결과(양성, 음성)가 적힌 용어가 공고 문맥을 따른다.
    """
    seen = {match.entry.id for match in context.matches}
    found = context.retriever.search(context.notice_text, args.query, context.species)
    new_matches = [match for match in found if match.entry.id not in seen]
    if not new_matches:
        return NOTHING_FOUND
    new_evidence = build_evidence(new_matches, context.species, start=len(context.evidence) + 1)
    context.matches.extend(new_matches)
    context.evidence.extend(new_evidence)
    if not new_evidence:
        return NOTHING_FOUND
    return format_evidence(new_evidence)


ADOPTION_GUIDE = ToolSpec(
    name="adoption_guide",
    description=(
        "보호소 입양 자격, 공고 기간, 입양비 지원, 입양자 준수사항, 동물등록, 예비입양제에 관한 공식 안내를 "
        "주제 이름으로 가져온다. 출처가 붙은 문장을 돌려준다."
    ),
    args_schema=AdoptionGuideArgs,
)


def adoption_guide(args: AdoptionGuideArgs, context: ToolContext) -> str:
    """공식 입양 안내 한 주제. 요청마다 달라지는 값은 쓰지 않는다."""
    return adoption_guide_text(args.topic)


# 이름 → (명세, 함수). 그래프의 도구 노드는 여기서만 찾는다.
TOOLS = {
    SEARCH_HEALTH.name: (SEARCH_HEALTH, search_health),
    ADOPTION_GUIDE.name: (ADOPTION_GUIDE, adoption_guide),
}
