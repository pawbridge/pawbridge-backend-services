from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field, field_validator

from app.chatbot.service import ChatbotService
from app.internal_auth import require_internal_key

router = APIRouter()


class AnimalContext(BaseModel):
    species: str | None = None
    breed: str | None = None
    age: str | None = None
    weight: str | None = None
    color: str | None = None
    gender: str | None = None
    neutered: str | None = None
    specialMark: str | None = None
    processState: str | None = None


class ShelterContext(BaseModel):
    """공고를 올린 보호소 정보. 에이전트 프롬프트에만 쓴다(고정 흐름은 쓰지 않음).

    백엔드가 아직 보내지 않아도 되도록 모두 선택 필드다. 담당자 이름과 이메일은 받지 않는다.
    """

    shelterName: str | None = None
    shelterPhone: str | None = None
    shelterAddress: str | None = None
    noticeEndDate: str | None = None
    adoptionProcedure: str | None = None
    operatingHours: str | None = None


class RecentMessage(BaseModel):
    role: str
    content: str

    @field_validator("role")
    @classmethod
    def validate_role(cls, value: str) -> str:
        if value not in {"user", "assistant"}:
            raise ValueError("role must be 'user' or 'assistant'")
        return value

    @field_validator("content")
    @classmethod
    def validate_content(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("content must not be blank")
        return value


class ChatbotMessageRequest(BaseModel):
    # 지금 공고 동물 id. 비슷한 동물 도구가 쓴다. 백엔드가 아직 보내지 않아도 되도록 선택 필드다.
    animalId: int | None = Field(default=None, gt=0)
    animalContext: AnimalContext
    shelterContext: ShelterContext | None = None
    recentMessages: list[RecentMessage] = Field(default_factory=list, max_length=6)
    question: str

    @field_validator("question")
    @classmethod
    def validate_question(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("question must not be blank")
        return value


class HealthSource(BaseModel):
    org: str
    title: str
    url: str
    checkedAt: str
    language: str


class HealthCard(BaseModel):
    id: str
    title: str
    summary: list[str]
    cautions: list[str]
    askShelter: list[str]
    # notice: 공고에 적힌 이 동물의 상태, question: 질문에 대한 일반 정보
    basis: str = "notice"


class ChatbotMessageResponse(BaseModel):
    answer: str
    safetyNotice: str
    provider: str
    # 건강 해설 RAG를 켰을 때만 채워진다. 끄면 빈 목록이라 기존 호출부는 그대로 동작한다.
    sources: list[HealthSource] = Field(default_factory=list)
    healthCards: list[HealthCard] = Field(default_factory=list)


@router.post(
    "/messages",
    response_model=ChatbotMessageResponse,
    dependencies=[Depends(require_internal_key)],
)
async def create_chatbot_message(req: ChatbotMessageRequest):
    try:
        return await ChatbotService().create_message(req)
    except NotImplementedError as exc:
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail=str(exc),
        ) from exc
