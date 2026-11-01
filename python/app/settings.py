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
    dinov3_checkpoint: str = Field("", alias="DINOV3_CHECKPOINT")
    sam3_checkpoint: str = Field("", alias="SAM3_CHECKPOINT")
    animal_focus_checkpoint: str = Field("", alias="ANIMAL_FOCUS_CHECKPOINT")


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
    gemini_api_key: str = Field("", alias="GEMINI_API_KEY")
    gemini_model: str = Field("gemini-3.5-flash-lite", alias="GEMINI_MODEL")
    gemini_timeout_seconds: float = Field(15, alias="GEMINI_TIMEOUT_SECONDS")
