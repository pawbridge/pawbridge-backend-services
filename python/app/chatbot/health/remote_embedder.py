"""별도 임베딩 서비스에 HTTP로 벡터를 묻는 Embedder. 모델은 python-api가 아니라 그 서비스에만 올린다.

검색 로직(규칙, 하이브리드 합치기, 기준값, 관문)은 Python에 있고, 이 클래스는 글을 보내 벡터만 받는다.
서비스가 느리거나 죽으면 예외를 올리고, 부르는 쪽(SafeHybridRetriever)이 규칙 검색만으로 돌아간다.
"""

import httpx
import numpy as np

# 질문 하나 임베딩은 데운 뒤 약 20ms(11/11 로컬 실측, bge-m3, MPS). 에이전트 마감을 오래 잡지 않게 짧게 둔다.
QUERY_TIMEOUT_SECONDS = 2.0
# 기동할 때 해설 조각 전부를 한 번에 보낸다(조각 수백 개).
PASSAGE_TIMEOUT_SECONDS = 60.0


class RemoteEmbedder:
    def __init__(self, base_url: str):
        self._url = base_url.rstrip("/") + "/embed"
        self._cache: dict[tuple[str, str], np.ndarray] = {}

    def embed(self, texts: list[str], kind: str) -> np.ndarray:
        missing = [text for text in texts if (kind, text) not in self._cache]
        if missing:
            timeout = PASSAGE_TIMEOUT_SECONDS if kind == "passage" else QUERY_TIMEOUT_SECONDS
            response = httpx.post(self._url, json={"texts": missing, "kind": kind}, timeout=timeout)
            response.raise_for_status()
            vectors = response.json()["vectors"]
            for text, vector in zip(missing, vectors):
                self._cache[(kind, text)] = np.asarray(vector, dtype=np.float32)
        return np.stack([self._cache[(kind, text)] for text in texts])
