from contextlib import asynccontextmanager

from fastapi import FastAPI
from app.chatbot import router as chatbot
from app.chatbot.service import load_health_retriever_if_enabled
from app.internal_auth import require_configured_key


@asynccontextmanager
async def startup_checks(app):
    """키가 없거나, 건강 해설 RAG가 켜져 있는데 해설이 잘못됐거나 없으면 기동에서 멈춘다(요청마다 실패하지 않게)."""
    require_configured_key()
    load_health_retriever_if_enabled()
    yield


app = FastAPI(
    title="Pawbridge AI Service",
    description="유기동물 이미지 유사도 기반 추천 서비스",
    version="1.0.0",
    lifespan=startup_checks,
)

# Lost search and recommendations live in app.entrypoints.vision_api, beside DINOv3.
app.include_router(chatbot.router, prefix="/internal/chatbot", tags=["chatbot"])


@app.get("/health")
def health_check():
    return {"status": "ok"}
