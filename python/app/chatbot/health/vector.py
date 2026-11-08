"""벡터 검색기와 하이브리드 검색기.

임베딩 모델은 Embedder로 바꿔 끼운다. 이 모듈은 numpy만 쓰고 모델 라이브러리에 의존하지 않는다.
"""

from dataclasses import dataclass
from typing import Literal, Protocol

import numpy as np

from app.chatbot.health.knowledge import HealthEntry
from app.chatbot.health.retriever import MAX_RESULTS, HealthMatch, RuleHealthRetriever, normalize_species

ChunkMode = Literal["whole", "split", "fact"]

# 양성, 음성, 접종으로 뜻이 갈리는 해설은 벡터가 고르지 않는다(규칙만 고른다).
RULE_ONLY_CONTEXTS = {"positive", "negative", "vaccination"}


class Embedder(Protocol):
    name: str

    def embed(self, texts: list[str], kind: Literal["query", "passage"]) -> np.ndarray:
        """정규화된 벡터(행마다 길이 1)를 돌려준다."""
        ...


@dataclass(frozen=True)
class Chunk:
    entry: HealthEntry
    text: str


def build_chunks(entries: list[HealthEntry], mode: ChunkMode) -> list[Chunk]:
    """해설을 임베딩할 조각으로 나눈다.

    whole: 해설 하나를 조각 하나로(제목, 요약, 주의점).
    split: 요약, 주의점, 보호소에 물어볼 것을 따로(해설 점수는 조각 점수의 최댓값).
    fact: 사실 문장 하나마다 조각 하나(앞에 해설 제목을 붙여 어느 병 이야기인지 남김).
    """
    chunks = []
    for entry in entries:
        summary = " ".join(fact.text for fact in entry.summary)
        cautions = " ".join(fact.text for fact in entry.cautions)
        if mode == "fact":
            for fact in [*entry.summary, *entry.cautions]:
                chunks.append(Chunk(entry, f"{entry.title}. {fact.text}"))
            continue
        if mode == "whole":
            chunks.append(Chunk(entry, f"{entry.title}. {summary} {cautions}"))
            continue
        chunks.append(Chunk(entry, f"{entry.title}. {summary}"))
        chunks.append(Chunk(entry, f"{entry.title}. {cautions}"))
        if entry.ask_shelter:
            chunks.append(Chunk(entry, f"{entry.title}. " + " ".join(entry.ask_shelter)))
    return chunks


class VectorHealthRetriever:
    """질문과 해설 조각의 코사인 유사도로 찾는다."""

    def __init__(
        self,
        entries: list[HealthEntry],
        embedder: Embedder,
        mode: ChunkMode,
        threshold: float,
        off_topic_examples: list[str] | None = None,
        off_topic_margin: float = 0.0,
    ):
        self._entries = [entry for entry in entries if entry.match.context not in RULE_ONLY_CONTEXTS]
        self._chunks = build_chunks(self._entries, mode)
        self._embedder = embedder
        self._threshold = threshold
        self._matrix = embedder.embed([chunk.text for chunk in self._chunks], "passage")
        # 반대 예시 관문: 건강과 무관한 질문 예시. 질문이 이쪽에 더 가까우면 해설을 붙이지 않는다.
        self._off_topic = embedder.embed(off_topic_examples, "query") if off_topic_examples else None
        self._off_topic_margin = off_topic_margin

    def is_off_topic(self, query: str) -> bool:
        if self._off_topic is None:
            return False
        query_vector = self._embedder.embed([query], "query")[0]
        off_topic_score = float(np.max(self._off_topic @ query_vector))
        health_score = float(np.max(self._matrix @ query_vector))
        return off_topic_score >= health_score + self._off_topic_margin

    def scores(self, query: str, species: str | None) -> list[tuple[HealthEntry, float]]:
        """해설마다 가장 높은 조각 점수를 높은 순으로 돌려준다."""
        wanted_species = normalize_species(species)
        query_vector = self._embedder.embed([query], "query")[0]
        similarities = self._matrix @ query_vector
        best: dict[str, tuple[HealthEntry, float]] = {}
        for chunk, score in zip(self._chunks, similarities):
            entry = chunk.entry
            if wanted_species and wanted_species not in entry.species:
                continue
            if entry.id not in best or score > best[entry.id][1]:
                best[entry.id] = (entry, float(score))
        return sorted(best.values(), key=lambda item: item[1], reverse=True)

    def search(self, notice_text: str | None, question: str | None, species: str | None) -> list[HealthMatch]:
        query = (question or notice_text or "").strip()
        if not query or self.is_off_topic(query):
            return []
        results = []
        for entry, score in self.scores(query, species):
            if score < self._threshold:
                break
            results.append(HealthMatch(entry, score, f"벡터 유사도 {score:.3f}"))
            if len(results) == MAX_RESULTS:
                break
        return results


class HybridHealthRetriever:
    """규칙 결과를 먼저 두고, 남은 자리를 벡터 결과로 채운다."""

    def __init__(self, rule: RuleHealthRetriever, vector: VectorHealthRetriever):
        self._rule = rule
        self._vector = vector

    def search(self, notice_text: str | None, question: str | None, species: str | None) -> list[HealthMatch]:
        results = list(self._rule.search(notice_text, question, species))
        if not question or len(results) >= MAX_RESULTS:
            return results[:MAX_RESULTS]
        # 규칙이 '없음', '음성', '접종'으로 막은 해설은 벡터도 넣지 못한다.
        seen = {match.entry.id for match in results} | self._rule.blocked_entry_ids(notice_text, question, species)
        for match in self._vector.search(None, question, species):
            if match.entry.id in seen:
                continue
            results.append(match)
            seen.add(match.entry.id)
            if len(results) == MAX_RESULTS:
                break
        return results
