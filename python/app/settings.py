"""Environment settings for every entrypoint, declared in one place.

Each model reads os.environ when loaded, so tests and restarts see the current environment.
Errors name variables only: values can be DSNs or keys. A model receives only its own variables,
so a validation error never holds another model's secrets.
"""
import os
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator


class _Settings(BaseModel):
    model_config = ConfigDict(frozen=True, hide_input_in_errors=True)

    @classmethod
    def load(cls):
        names = [field.alias or name for name, field in cls.model_fields.items()]
        try:
            return cls.model_validate({name: os.environ[name] for name in names if name in os.environ})
        except ValidationError as error:
            problems = sorted({str(e["loc"][0]) if e["loc"] else e["msg"].removeprefix("Value error, ")
                               for e in error.errors()})
            raise RuntimeError(f"Invalid or missing settings: {', '.join(problems)}") from None


class InternalApiSettings(_Settings):
    key: str = Field("", alias="INTERNAL_API_KEY")


class VisionSettings(_Settings):
    device: Literal["cuda", "mps"] = Field("cuda", alias="LOST_SEARCH_DEVICE")
    # The launcher must set this before the process imports torch.
    mps_fallback: str = Field("", alias="PYTORCH_ENABLE_MPS_FALLBACK")
    visual_profile: Literal["original", "animal-focus", "sam3-animal-focus"] = Field(
        "original", alias="LOST_SEARCH_VISUAL_PROFILE")
    index: str = Field("animals-lost-dinov3-large-v1", pattern=r"^animals-lost-dinov3-[a-z0-9][a-z0-9-]{0,80}$",
                       alias="LOST_SEARCH_INDEX")
    # "token" uses the CLS token instead of the patch average; its vectors need their own gallery.
    pooling: Literal["avg", "token"] = Field("avg", alias="LOST_SEARCH_POOLING")
    # DINOv3 입력 크기. 384는 SAM 3 + CLS 조합에서만 검증했고(2026-11-07 보류 세트), 모델 버전과 갤러리가 따로다.
    input_size: int = Field(256, alias="LOST_SEARCH_INPUT_SIZE")
    dinov3_checkpoint: str = Field("", alias="DINOV3_CHECKPOINT")
    sam3_checkpoint: str = Field("", alias="SAM3_CHECKPOINT")
    # 비우면 끈다. 수동 갤러리 빌드에서만 켜서 SAM 3 출력(박스, 마스크, 점수)을 사진별로 남기고,
    # 다음 재계산(DINOv3 교체 등)에서 SAM 3를 다시 돌리지 않는다.
    focus_cache_dir: str = Field("", alias="LOST_SEARCH_FOCUS_CACHE_DIR")
    animal_focus_checkpoint: str = Field("", alias="ANIMAL_FOCUS_CHECKPOINT")

    @model_validator(mode="after")
    def supported_input_size(self):
        if self.input_size not in (256, 384):
            raise ValueError("LOST_SEARCH_INPUT_SIZE must be 256 or 384")
        if self.input_size == 384 and (self.visual_profile != "sam3-animal-focus" or self.pooling != "token"):
            raise ValueError("LOST_SEARCH_INPUT_SIZE=384 requires sam3-animal-focus with token pooling")
        return self


class CoatColorSettings(_Settings):
    # No default: the 2026-10-17 Mac migration silently dropped it and searches ran with 0.
    ranking_weight: float = Field(alias="LOST_SEARCH_COAT_COLOR_WEIGHT", ge=0, le=.2, allow_inf_nan=False)


class GallerySyncSettings(_Settings):
    enabled: bool = Field(False, alias="LOST_GALLERY_SYNC_ENABLED")
    state_dir: str = Field("", alias="LOST_GALLERY_STATE_DIR")
    protocol: Literal["v1", "v2"] = Field("v1", alias="LOST_GALLERY_PROTOCOL")
    source_url: str = Field("", alias="LOST_GALLERY_SOURCE_URL")
    source_key: str = Field("", alias="LOST_GALLERY_SOURCE_KEY")
    r2_host: str = Field("", alias="LOST_GALLERY_R2_HOST")
    photo_root: str = Field("", alias="LOST_GALLERY_PHOTO_ROOT")
    interval_seconds: int = Field(900, alias="LOST_GALLERY_INTERVAL_SECONDS")

    @model_validator(mode="after")
    def source_when_enabled(self):
        if self.enabled:
            missing = [field.alias for name, field in type(self).model_fields.items()
                       if name in {"source_url", "source_key", "r2_host", "photo_root"} and not getattr(self, name)]
            if missing:
                raise ValueError(f"gallery refresh requires {', '.join(missing)}")
        return self


class PostgresSettings(_Settings):
    # Elasticsearch storage was removed; a leftover setting must fail instead of being ignored.
    backend: Literal["postgresql"] = Field("postgresql", alias="LOST_STORAGE_BACKEND")
    dsn: str = Field("", alias="LOST_PG_DSN")
    pool_max_size: int = Field(2, ge=1, le=4, alias="LOST_PG_POOL_MAX_SIZE")


class ChatbotSettings(_Settings):
    llm_provider: str = Field("stub", alias="LLM_PROVIDER")
    # 에이전트 모드의 예비 제공자(예: openai). 주 제공자가 일시 장애(429, 5xx, 연결 실패, 시간 초과)로 실패하면
    # 질문을 처음부터 이 제공자로 다시 푼다. 비우면 예비 없이 지금처럼 끝낸다. 키는 그 제공자의 키를 쓴다.
    llm_fallback_provider: str = Field("", alias="LLM_FALLBACK_PROVIDER")
    gemini_api_key: str = Field("", alias="GEMINI_API_KEY")
    gemini_model: str = Field("gemini-3.5-flash-lite", alias="GEMINI_MODEL")
    gemini_timeout_seconds: float = Field(15, alias="GEMINI_TIMEOUT_SECONDS")
    # OpenAI(LLM_PROVIDER=openai). 모델 이름만 바꾸면 같은 키로 다른 모델을 쓴다.
    openai_api_key: str = Field("", alias="OPENAI_API_KEY")
    openai_model: str = Field("gpt-6-luna", alias="OPENAI_MODEL")
    openai_timeout_seconds: float = Field(15, alias="OPENAI_TIMEOUT_SECONDS")
    # Anthropic(LLM_PROVIDER=anthropic). 모델 이름만 바꾸면 같은 키로 다른 Claude 모델을 쓴다.
    anthropic_api_key: str = Field("", alias="ANTHROPIC_API_KEY")
    anthropic_model: str = Field("claude-haiku-5-5", alias="ANTHROPIC_MODEL")
    anthropic_timeout_seconds: float = Field(15, alias="ANTHROPIC_TIMEOUT_SECONDS")
    # 공고 건강 해설 RAG. 기본은 꺼짐이고, 켜면 승인된 해설을 근거로 답한다.
    health_rag_enabled: bool = Field(False, alias="HEALTH_RAG_ENABLED")
    # 끌 해설 id를 쉼표로 적는다(예: heartworm-positive,cough). 틀린 해설 하나를 배포 없이 내리고 재시작한다.
    # 승인된 해설에 없는 id를 적으면 기동에서 멈춘다(오타로 끄지 못한 채 도는 일을 막는다).
    health_rag_disabled_ids: str = Field("", alias="HEALTH_RAG_DISABLED_IDS")
    # 답을 만드는 방식. rag는 모델을 한 번 부르는 고정 흐름, agent는 모델이 건강 해설 검색 도구를
    # 골라 부르는 입양 상담 에이전트다. agent는 해설 검색을 도구로 쓰므로 HEALTH_RAG_ENABLED가 켜져 있어야 한다.
    chatbot_mode: Literal["rag", "agent"] = Field("rag", alias="CHATBOT_MODE")
    # 에이전트 모델의 추론(생각) 단계. 제공자마다 이름이 달라 agent/models.py가 바꿔 넘긴다.
    # 단계가 높을수록 생각 토큰(출력 요금)이 늘어난다. 기본은 low다: 11/11 시범 실행에서 minimal은
    # 첫 검색이 놓친 건강 질문 18개에 도구를 한 번도 부르지 않았고, low와 강화한 도구 규칙을 함께 쓰니
    # 12개에서 불러 8개의 정답 해설을 찾았다(고정 흐름은 그대로 모델을 한 번 부른다).
    # 에이전트 검색 도구의 하이브리드 검색이 부르는 임베딩 서비스(bge-m3만 올린 별도 프로세스). 비우면 규칙 검색만.
    embedding_service_url: str = Field("", alias="EMBEDDING_SERVICE_URL")
    chatbot_reasoning: Literal["minimal", "low", "medium", "high"] = Field("low", alias="CHATBOT_REASONING")
    # 비슷한 동물 도구가 부르는 백엔드 주소(예: http://monolith:8080). 비우면 도구는 추천을 못 불러온다고 답한다.
    backend_base_url: str = Field("", alias="CHATBOT_BACKEND_BASE_URL")

    def disabled_health_entry_ids(self) -> frozenset[str]:
        return frozenset(item.strip() for item in self.health_rag_disabled_ids.split(",") if item.strip())

    @model_validator(mode="after")
    def agent_needs_health_rag(self):
        if self.chatbot_mode == "agent" and not self.health_rag_enabled:
            raise ValueError("CHATBOT_MODE=agent requires HEALTH_RAG_ENABLED=true")
        return self

    @model_validator(mode="after")
    def fallback_differs_from_provider(self):
        # 같은 제공자를 예비로 두면 같은 장애를 한 번 더 기다리기만 한다.
        fallback = self.llm_fallback_provider.strip().lower()
        if fallback and fallback == (self.llm_provider.strip().lower() or "stub"):
            raise ValueError("LLM_FALLBACK_PROVIDER must differ from LLM_PROVIDER")
        return self
