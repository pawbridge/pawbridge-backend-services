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
    animalContext: AnimalContext
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
