"""임베딩 서비스의 요청 계약과 입력 제한. 실제 모델 없이 가짜 인코더로 본다(검색 품질 검증이 아니다)."""

import threading
import unittest
import unittest.mock

import numpy as np
from fastapi.testclient import TestClient

from app.chatbot.health.remote_embedder import RemoteEmbedder
from app.entrypoints import embedding_api


class FakeEncoder:
    name = "fake-bge"
    dimension = 3

    def __init__(self, fail=False, gate=None):
        self.fail = fail
        self.gate = gate
        self.calls = []

    def encode(self, texts):
        if self.gate is not None:
            self.gate.wait(timeout=5)
        if self.fail:
            raise RuntimeError("mps out of memory: secret detail")
        self.calls.append(list(texts))
        return [[1.0, 0.0, 0.0] for _ in texts]


def client_with(encoder):
    return TestClient(embedding_api.create_app(load_encoder=lambda: encoder))


class EmbeddingApiTest(unittest.TestCase):
    def test_embed_returns_one_vector_per_text(self):
        encoder = FakeEncoder()
        with client_with(encoder) as client:
            response = client.post("/embed", json={"texts": ["심장사상충", "피부병"], "kind": "passage"})
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(len(body["vectors"]), 2)
        self.assertEqual(body["dimension"], 3)
        self.assertEqual(encoder.calls, [["심장사상충", "피부병"]])

    def test_health_reports_model(self):
        with client_with(FakeEncoder()) as client:
            response = client.get("/health")
        self.assertEqual(response.json(), {"status": "ok", "model": "fake-bge", "dimension": 3})

    def test_rejects_bad_input(self):
        too_many = ["가"] * (embedding_api.MAX_TEXTS + 1)
        too_long = ["가" * (embedding_api.MAX_TEXT_CHARS + 1)]
        cases = [
            ({"texts": [], "kind": "query"}, 422),
            ({"texts": too_many, "kind": "query"}, 422),
            ({"texts": ["   "], "kind": "query"}, 422),
            ({"texts": ["질문"], "kind": "document"}, 422),
            ({"texts": too_long, "kind": "query"}, 413),
        ]
        encoder = FakeEncoder()
        with client_with(encoder) as client:
            for payload, status in cases:
                with self.subTest(status=status, kind=payload["kind"]):
                    self.assertEqual(client.post("/embed", json=payload).status_code, status)
        self.assertEqual(encoder.calls, [])

    def test_model_error_hides_detail(self):
        with client_with(FakeEncoder(fail=True)) as client:
            response = client.post("/embed", json={"texts": ["질문"], "kind": "query"})
        self.assertEqual(response.status_code, 503)
        self.assertNotIn("secret", response.text)

    def test_rejects_when_queue_is_full(self):
        gate = threading.Event()
        encoder = FakeEncoder(gate=gate)
        app = embedding_api.create_app(load_encoder=lambda: encoder)
        results = []
        with TestClient(app) as client:

            def call():
                results.append(client.post("/embed", json={"texts": ["질문"], "kind": "query"}).status_code)

            workers = [threading.Thread(target=call) for _ in range(embedding_api.MAX_WAITING)]
            for worker in workers:
                worker.start()
            # 앞선 요청들이 자리를 다 잡을 때까지 잠깐 기다린 뒤 하나 더 보낸다(첫 요청은 인코더 안에서 멈춰 있다).
            threading.Event().wait(0.5)
            extra = client.post("/embed", json={"texts": ["질문"], "kind": "query"})
            gate.set()
            for worker in workers:
                worker.join(timeout=5)
        self.assertEqual(extra.status_code, 503)
        self.assertEqual(sorted(results), [200] * embedding_api.MAX_WAITING)

    def test_missing_model_path_stops_startup(self):
        app = embedding_api.create_app()
        with unittest.mock.patch.dict("os.environ", {"EMBEDDING_MODEL_PATH": ""}):
            with self.assertRaises(RuntimeError):
                with TestClient(app):
                    pass


class RemoteEmbedderContractTest(unittest.TestCase):
    """python-api의 RemoteEmbedder가 보내는 요청을 이 서비스가 그대로 받는지 본다."""

    def test_remote_embedder_round_trip(self):
        encoder = FakeEncoder()
        with client_with(encoder) as client:
            embedder = RemoteEmbedder("http://embedding.test/")

            def post(url, json, timeout):
                self.assertEqual(url, "http://embedding.test/embed")
                return client.post("/embed", json=json)

            with unittest.mock.patch("app.chatbot.health.remote_embedder.httpx.post", side_effect=post):
                vectors = embedder.embed(["개 뒷다리 마비", "피부병"], "query")
                again = embedder.embed(["개 뒷다리 마비"], "query")
        self.assertEqual(vectors.shape, (2, 3))
        self.assertEqual(again.shape, (1, 3))
        self.assertTrue(np.allclose(np.linalg.norm(vectors, axis=1), 1.0))
        # 이미 받은 글은 다시 보내지 않는다(RemoteEmbedder 캐시).
        self.assertEqual(encoder.calls, [["개 뒷다리 마비", "피부병"]])


if __name__ == "__main__":
    unittest.main()
