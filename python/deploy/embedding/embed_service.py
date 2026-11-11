"""bge-m3 임베딩만 하는 작은 서비스(시제품). 챗봇 로직은 python-api에 있고, 이 서비스는 글을 받아 벡터를 돌려준다.
POST /embed {"texts": [...], "kind": "query"|"passage"} -> {"vectors": [[...]], "ms": 시간}
"""
import time
from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

MODEL = Path.home() / ".local/share/pawbridge-ai/eval-models-20261101/embed/bge-m3-5617a9f61b02"
model = SentenceTransformer(str(MODEL), device="mps")
app = FastAPI()


class EmbedRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=512)
    kind: str = "query"


@app.post("/embed")
def embed(req: EmbedRequest):
    started = time.monotonic()
    vectors = model.encode(req.texts, normalize_embeddings=True, convert_to_numpy=True, batch_size=32)
    return {"vectors": vectors.tolist(), "ms": round((time.monotonic() - started) * 1000, 1)}


@app.get("/health")
def health():
    return {"ok": True}
