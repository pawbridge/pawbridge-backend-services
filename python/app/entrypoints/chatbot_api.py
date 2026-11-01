from fastapi import FastAPI
from app.chatbot import router as chatbot

app = FastAPI(
    title="Pawbridge AI Service",
    description="유기동물 이미지 유사도 기반 추천 서비스",
    version="1.0.0"
)

# Lost search and recommendations live in app.entrypoints.vision_api, beside DINOv3.
app.include_router(chatbot.router, prefix="/internal/chatbot", tags=["chatbot"])


@app.get("/health")
def health_check():
    return {"status": "ok"}
