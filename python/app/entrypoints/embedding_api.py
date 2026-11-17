"""건강 해설 하이브리드 검색의 임베딩 서비스. Mac 네이티브(MPS) 프로세스 하나로 실행한다.

uvicorn app.entrypoints.embedding_api:app --host 127.0.0.1 --port 18900 --workers 1

python-api(챗봇)는 RemoteEmbedder로 글만 보내 정규화된 벡터를 받는다. 검색 로직(규칙, 하이브리드 합치기,
기준값, 관문)은 python-api에 있고, 이 서비스는 모델만 올린다(python-api 메모리 한도 512MiB, bge-m3 2.1GB).
돌려주는 것은 공개된 해설 문장과 질문의 벡터뿐이고 비밀값을 다루지 않는다. 그래도 루프백에만 연다.
"""

import os
import threading
from contextlib import asynccontextmanager
from typing import Literal, Protocol

import anyio
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

# 기동할 때 python-api가 해설 조각 전부를 한 번에 보낸다. 해설이 늘어도 넉넉하게 둔다.
MAX_TEXTS = 1024
# 해설 조각 하나는 수백 자다. 질문 하나가 이보다 길면 받지 않는다.
MAX_TEXT_CHARS = 4000
# 질문 하나가 오래 기다리지 않게 한 번에 하나만 계산하고, 줄이 길면 바로 거절한다(python-api는 규칙 검색으로 돌아간다).
MAX_WAITING = 4
ENCODE_BATCH_SIZE = 32


class Encoder(Protocol):
    name: str
    dimension: int

    def encode(self, texts: list[str]) -> list[list[float]]:
        """행마다 길이 1로 정규화된 벡터를 돌려준다."""
        ...


class SentenceTransformerEncoder:
    """bge-m3(CLS 풀링, 정규화)를 sentence-transformers로 올린다. 질문과 해설에 접두어를 붙이지 않는다."""

    def __init__(self, model_path: str, device: str):
        from sentence_transformers import SentenceTransformer

        self._model = SentenceTransformer(model_path, device=device)
        self.name = os.path.basename(model_path.rstrip("/"))
        self.dimension = int(self._model.get_embedding_dimension())
        # 첫 요청이 모델 준비(MPS 커널 컴파일)를 떠안지 않게 기동 때 한 번 돌린다.
        self.encode(["준비"])

    def encode(self, texts: list[str]) -> list[list[float]]:
        vectors = self._model.encode(
            texts, batch_size=ENCODE_BATCH_SIZE, normalize_embeddings=True, convert_to_numpy=True
        )
        return vectors.astype("float32").tolist()


class EmbedRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=MAX_TEXTS)
    kind: Literal["query", "passage"]


def load_encoder_from_env() -> Encoder:
    model_path = os.environ.get("EMBEDDING_MODEL_PATH", "").strip()
    if not model_path:
        raise RuntimeError("EMBEDDING_MODEL_PATH is required")
    # CPU로 자동 대체하지 않는다(실종검색 실행기와 같은 원칙). 장치가 없으면 기동하지 않는다.
    device = os.environ.get("EMBEDDING_DEVICE", "mps").strip()
    return SentenceTransformerEncoder(model_path, device)


def create_app(load_encoder=load_encoder_from_env) -> FastAPI:
    state: dict = {}

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        state["encoder"] = load_encoder()
        yield

    app = FastAPI(title="PawBridge Health Embedding", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    # 모델 계산은 한 번에 하나. 기다리는 요청 수는 따로 센다.
    compute = threading.Lock()
    waiting = anyio.Semaphore(MAX_WAITING)

    @app.get("/health")
    async def health():
        encoder = state["encoder"]
        return {"status": "ok", "model": encoder.name, "dimension": encoder.dimension}

    @app.post("/embed")
    async def embed(body: EmbedRequest):
        # kind는 RemoteEmbedder 계약에 맞춰 받는다. bge-m3는 질문과 해설에 같은 방식으로 임베딩한다.
        if any(not text.strip() for text in body.texts):
            raise HTTPException(422, "Empty text")
        if any(len(text) > MAX_TEXT_CHARS for text in body.texts):
            raise HTTPException(413, f"Text exceeds {MAX_TEXT_CHARS} characters")
        try:
            waiting.acquire_nowait()
        except anyio.WouldBlock as exc:
            raise HTTPException(503, "Embedding service is busy", headers={"Retry-After": "1"}) from exc
        try:
            encoder = state["encoder"]

            def run():
                with compute:
                    return encoder.encode(body.texts)

            try:
                vectors = await anyio.to_thread.run_sync(run)
            except Exception as exc:  # noqa: BLE001 - 모델 오류 내용은 응답에 싣지 않는다
                raise HTTPException(503, "Embedding failed") from exc
        finally:
            waiting.release()
        return {"vectors": vectors, "model": encoder.name, "dimension": encoder.dimension}

    return app


app = create_app()
