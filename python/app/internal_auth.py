"""X-Internal-Api-Key check shared by every internal API (chatbot, photo, vision)."""
import hmac
from contextlib import asynccontextmanager

from fastapi import Header, HTTPException

from app.settings import InternalApiSettings


def require_configured_key():
    """Call at startup so a missing key stops the process instead of failing each request."""
    if not InternalApiSettings.load().key.strip():
        raise RuntimeError("Internal authentication must be configured")


@asynccontextmanager
async def require_key_at_startup(app):
    """Lifespan for the APIs whose only startup check is the key (chatbot, photo)."""
    require_configured_key()
    yield


def require_internal_key(x_internal_api_key: str | None = Header(None)):
    expected = InternalApiSettings.load().key
    if not expected.strip():
        raise HTTPException(503, "내부 API 설정을 확인해 주세요")
    if not x_internal_api_key or not hmac.compare_digest(x_internal_api_key.encode(), expected.encode()):
        raise HTTPException(401, "인증이 필요합니다")
