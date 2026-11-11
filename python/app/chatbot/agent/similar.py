"""비슷한 동물 도구의 본체. 지금 공고 동물과 사진 생김새가 비슷한 보호 동물을 백엔드 추천 API에서 가져온다.

추천 순서는 실종검색과 같은 이미지 특징(SAM 3 동물 영역 + DINOv3)으로 vision_api가 정하고,
공고중과 보호중만 남기는 것은 백엔드가 한다. 그래서 이 도구는 vision_api가 아니라 백엔드 공개 API를 부른다.
모델이 정하는 입력은 소개할 수뿐이다. 어느 동물인지는 요청의 animalId로 코드가 넣는다.
"""

import logging
import re

import httpx
from pydantic import BaseModel, Field

from app.settings import ChatbotSettings

logger = logging.getLogger("uvicorn.error")

# 추천 API는 저장된 특징만 비교해 보통 0.2초 안에 끝난다. 에이전트 마감(20초)을 오래 잡지 않게 짧게 둔다.
TIMEOUT_SECONDS = 3.0
# 추천 API가 돌려주는 최대 수(6)보다 적게 말하게 한다. 답이 길어지면 길이 상한에서 잘린다.
MAX_ANIMALS = 4
DEFAULT_ANIMALS = 3

# 비슷한 동물을 묻는 질문. "비슷한/닮은" 바로 뒤에 동물을 가리키는 낱말이 와야 한다.
# "비슷한 증상"처럼 건강 질문에 쓰인 "비슷한"은 걸리지 않게 한다. "다른 동물에게 옮나요?" 같은 질문이 있어
# "다른"만으로는 잡지 않는다.
SIMILAR_QUESTION = re.compile(r"(비슷|닮)\S*\s*(생긴\s*)?(다른\s*)?(아이|애|얘|친구|강아지|개(?!월)|고양이|냥이|동물|녀석)")

# 경기-양평-2025-00429 같은 공고번호. 답 검사에서 도구 결과에 없는 번호를 찾는 데 쓴다.
NOTICE_NUMBER = re.compile(r"[가-힣]{1,10}-[가-힣]{1,10}-\d{4}-\d{3,6}")

SPECIES_NAMES = {"DOG": "개", "CAT": "고양이"}
GENDER_NAMES = {"MALE": "수컷", "FEMALE": "암컷"}

NO_ANIMAL = "지금 보는 공고 번호를 몰라 비슷한 동물을 찾지 못했습니다. 동물 이름이나 공고번호를 지어내지 말고, 공고 목록에서 찾아보도록 안내하세요."
NOTHING_SIMILAR = "지금 공고중이거나 보호중인 동물 가운데 사진이 비슷한 동물을 찾지 못했습니다. 비슷한 동물이 없다고 답하고, 동물을 지어내지 마세요."
# 답에 도구 결과에 없는 공고번호가 나왔을 때 사용자에게 보내는 답. 건강 대체 답변은 이 질문에 맞지 않는다.
SIMILAR_FALLBACK = "비슷한 아이 소개를 확인하지 못했습니다. 공고 화면 아래의 비슷한 아이 목록을 보시거나 다시 물어봐 주세요."
UNAVAILABLE = "지금은 비슷한 동물 추천을 불러오지 못했습니다. 잠시 뒤 다시 물어봐 달라고 답하고, 동물을 지어내지 마세요."


class FindSimilarArgs(BaseModel):
    # 입력이 하나도 없으면 Gemini 함수 선언의 parameters.properties가 비어 거부될 수 있어 소개할 수를 받는다.
    count: int = Field(
        DEFAULT_ANIMALS,
        ge=1,
        le=MAX_ANIMALS,
        description=f"소개할 동물 수. 사용자가 수를 말하지 않으면 {DEFAULT_ANIMALS}",
    )


async def similar_animals_text(animal_id: int | None, count: int = DEFAULT_ANIMALS) -> tuple[str, list[str]]:
    """추천 API 결과를 모델이 읽을 글과, 그 글에 넣은 공고번호로 바꾼다.

    실패해도 오류를 올리지 않고 안내 문구를 돌려준다. 추천이 안 된다고 상담 전체가 실패할 일은 아니다.
    """
    if animal_id is None:
        return NO_ANIMAL, []
    base_url = ChatbotSettings.load().backend_base_url.rstrip("/")
    if not base_url:
        return UNAVAILABLE, []
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT_SECONDS) as client:
            response = await client.get(f"{base_url}/api/v1/animals/{animal_id}/similar")
            response.raise_for_status()
            cards = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        # 주소나 응답 본문은 남기지 않고 오류 종류만 남긴다.
        logger.warning("Chatbot similar animals failed: error=%s", type(exc).__name__)
        return UNAVAILABLE, []
    if not isinstance(cards, list):
        logger.warning("Chatbot similar animals failed: error=unexpected_body")
        return UNAVAILABLE, []

    shown = [card for card in cards if isinstance(card, dict) and card.get("apmsNoticeNo")][:min(count, MAX_ANIMALS)]
    if not shown:
        return NOTHING_SIMILAR, []
    header = "사진 생김새가 비슷한 보호 동물입니다. 이 목록에 있는 동물만, 공고번호를 그대로 옮겨 소개하세요."
    # 모델에게 보여 준 동물의 번호만 돌려준다. 잘라 낸 동물의 번호가 답에 나오면 지어낸 것이다.
    return "\n".join([header, *(describe(card) for card in shown)]), [card["apmsNoticeNo"] for card in shown]


def describe(card: dict) -> str:
    """한 마리를 한 줄로. 특이사항(specialMark)은 넣지 않는다: 병명이 섞이면 의학 표현 검사에 걸린다."""
    parts = [
        SPECIES_NAMES.get(card.get("species"), None),
        card.get("breed"),
        GENDER_NAMES.get(card.get("gender"), None),
        f"{card['birthYear']}년생" if card.get("birthYear") else None,
        f"털색 {card['color']}" if card.get("color") else None,
        f"체중 {card['weight']}" if card.get("weight") else None,
        card.get("shelterName"),
        f"공고 종료 {card['noticeEndDate']}" if card.get("noticeEndDate") else None,
    ]
    details = ", ".join(str(part) for part in parts if part)
    return f"- 공고번호 {card['apmsNoticeNo']}: {details}"


def asks_for_similar(question: str) -> bool:
    """비슷한 동물을 묻는 질문인지. 맞으면 첫 모델 호출에서 이 도구를 반드시 부르게 한다."""
    return SIMILAR_QUESTION.search(question) is not None


def unknown_notice_numbers(answer: str, allowed: set[str]) -> list[str]:
    """답에 나온 공고번호 중 도구 결과에 없는 번호. 모델이 번호를 지어내거나 한 자리라도 틀리면 걸린다."""
    return [found for found in NOTICE_NUMBER.findall(answer) if found not in allowed]
