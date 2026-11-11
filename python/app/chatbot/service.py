import logging
from functools import lru_cache

from fastapi import HTTPException, status

from app.chatbot.health.answer import (
    FALLBACK_ANSWER,
    build_evidence,
    check_answer,
    check_plain_answer,
    health_cards,
    sources_for,
    strip_citations,
)
from app.chatbot.health.knowledge import entries_version, load_entries
from app.chatbot.health.retriever import HealthMatch, RuleHealthRetriever
from app.chatbot.prompt_builder import build_prompt
from app.chatbot.provider import (
    ChatbotProviderConfigurationError,
    ChatbotProviderTruncatedError,
    ChatbotProviderUpstreamError,
    get_chatbot_provider,
)
from app.settings import ChatbotSettings

SAFETY_NOTICE = (
    "이 답변은 일반적인 참고 정보이며, 정확한 건강 상태나 치료 판단은 "
    "동물병원 또는 수의사에게 확인해 주세요."
)

# uvicorn 로그와 같은 출력으로 나가야 운영 로그(Loki)에 모인다(gemini_provider와 같은 로거).
logger = logging.getLogger("uvicorn.error")


@lru_cache(maxsize=1)
def health_retriever() -> RuleHealthRetriever:
    """승인된 해설로 규칙 검색기를 한 번만 만든다. HEALTH_RAG_DISABLED_IDS의 해설은 뺀다.

    고정 흐름과 에이전트 도구가 모두 이 검색기를 쓰므로, 뺀 해설은 근거, 출처, 카드 어디에도 나오지 않는다.
    해설이 잘못됐거나, 끄라는 id가 승인된 해설에 없거나(오타), 남는 해설이 없으면 실패한다.
    """
    entries = approved_entries()
    logger.info("Health RAG entries: loaded=%d disabled=%s", len(entries), ",".join(sorted(ChatbotSettings.load().disabled_health_entry_ids())) or "-")
    return RuleHealthRetriever(entries)


def approved_entries() -> list:
    """승인된 해설에서 HEALTH_RAG_DISABLED_IDS를 뺀 목록. 오타 id나 빈 목록이면 실패한다."""
    entries = load_entries()
    disabled = ChatbotSettings.load().disabled_health_entry_ids()
    unknown = sorted(disabled - {entry.id for entry in entries})
    if unknown:
        raise RuntimeError(f"HEALTH_RAG_DISABLED_IDS에 승인된 해설에 없는 id가 있습니다: {', '.join(unknown)}")
    entries = [entry for entry in entries if entry.id not in disabled]
    if not entries:
        raise RuntimeError("HEALTH_RAG_ENABLED인데 쓸 수 있는 승인된 건강 해설이 하나도 없습니다")
    return entries


from app.chatbot.health.retriever import MAX_RESULTS, HealthMatch  # noqa: E402


class SafeHybridRetriever:
    """하이브리드 검색. 임베딩 서비스가 실패하면 그 질문은 규칙 검색만으로 답한다(상담 전체를 실패시키지 않는다)."""

    def __init__(self, rule: RuleHealthRetriever, hybrid):
        self._rule = rule
        self._hybrid = hybrid

    def search(self, notice_text, question, species):
        try:
            return self._hybrid.search(notice_text, question, species)
        except Exception as exc:  # noqa: BLE001 - 임베딩 서비스 장애는 종류와 관계없이 규칙 검색으로 돌린다
            logger.warning("Health hybrid search failed: error=%s", type(exc).__name__)
            return self._rule.search(notice_text, question, species)

    def blocked_entry_ids(self, notice_text, question, species):
        return self._rule.blocked_entry_ids(notice_text, question, species)

    def search_tool(self, notice_text, query, original_question, species):
        """에이전트 검색 도구용 하이브리드. 규칙은 모델이 바꾼 검색어로 찾고, 벡터는 두 질의가 합의한 해설만 더한다.

        - 벡터로 더하는 해설은 많아야 하나다: 모델이 바꾼 검색어의 벡터 1위가 원래 질문의 벡터 상위 VECTOR_TOP_K 안에 있고
          원래 질문 기준 기준값을 넘을 때만. 11/11 로컬 실측에서 원래 질문만의 1위는 자주 틀렸고("앞이 안 보인다고"의
          1위 예후불량, 실명은 3위), 두 질의 상위 3개의 교집합도 3위 자리에 잡음이 남았다("털이 빠지고 긁어요"의 탈수).
        - 기준값과 범위 밖 관문은 원래 질문에만 건다(11/06~07에 질문 문장으로 고른 값이다).
        - 규칙이 공고의 "없음, 음성, 예방, 접종" 문맥으로 해설을 하나라도 막은 질문에는 벡터를 쓰지 않는다
          (예: "외부기생충 예방 도포 완료" 공고의 "피부는 괜찮나요?", "외상 없음" 공고의 "다친 데 없나요?").
        """
        results = list(self._rule.search(notice_text, query, species))
        if not original_question or len(results) >= MAX_RESULTS:
            return results[:MAX_RESULTS]
        blocked = self._rule.blocked_entry_ids(notice_text, original_question, species) | self._rule.blocked_entry_ids(
            notice_text, query, species
        )
        if blocked:
            return results
        try:
            vector = self._hybrid_vector()
            if vector.is_off_topic(original_question):
                return results
            if not query:
                return results
            by_question = {entry.id: score for entry, score in vector.scores(original_question, species)[:VECTOR_TOP_K]}
            query_top = vector.scores(query, species)[:1]
        except Exception as exc:  # noqa: BLE001 - 임베딩 서비스 장애는 종류와 관계없이 규칙 검색으로 돌린다
            logger.warning("Health hybrid search failed: error=%s", type(exc).__name__)
            return results
        if not query_top:
            return results
        entry = query_top[0][0]
        score = by_question.get(entry.id)
        if score is None or score < VECTOR_THRESHOLD or entry.id in {match.entry.id for match in results}:
            return results
        results.append(HealthMatch(entry, score, "vector-agreed", basis="question"))
        return results

    def _hybrid_vector(self):
        return self._hybrid._vector


@lru_cache(maxsize=1)
def agent_health_retriever():
    """에이전트 검색 도구가 쓰는 검색기. EMBEDDING_SERVICE_URL이 있으면 규칙 + 벡터 하이브리드, 없으면 규칙만.

    코드의 첫 검색(고정 흐름과 같은 단계)은 계속 규칙만 쓴다(11/09 결정: 하이브리드는 에이전트 도구에 붙인다).
    기준값과 관문 여유는 11/06~07 한국어 보류 세트에서 고른 값이다(bge-m3, split 0.475, 관문 +0.10).
    """
    rule = health_retriever()
    settings = ChatbotSettings.load()
    if not settings.embedding_service_url:
        return rule
    from pathlib import Path

    from app.chatbot.health.remote_embedder import RemoteEmbedder
    from app.chatbot.health.vector import HybridHealthRetriever, VectorHealthRetriever

    examples_path = Path(__file__).parent / "health" / "off_topic_examples.txt"
    examples = [line.strip() for line in examples_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    try:
        vector = VectorHealthRetriever(
            approved_entries(),
            RemoteEmbedder(settings.embedding_service_url),
            mode="split",
            threshold=VECTOR_THRESHOLD,
            off_topic_examples=examples,
            off_topic_margin=OFF_TOPIC_MARGIN,
        )
    except Exception as exc:  # noqa: BLE001 - 임베딩 서비스가 없으면 규칙 검색으로 시작한다
        logger.warning("Health hybrid search unavailable at startup: error=%s", type(exc).__name__)
        return rule
    logger.info("Health hybrid search ready: threshold=%s margin=%s", VECTOR_THRESHOLD, OFF_TOPIC_MARGIN)
    return SafeHybridRetriever(rule, HybridHealthRetriever(rule, vector))


VECTOR_THRESHOLD = 0.475
# 두 질의(원래 질문, 모델이 바꾼 검색어)의 벡터 상위 몇 개끼리 겹쳐 보는지.
VECTOR_TOP_K = 3
OFF_TOPIC_MARGIN = 0.10


@lru_cache(maxsize=1)
def health_knowledge_version() -> str:
    return entries_version()


def log_health_answer(matches: list[HealthMatch], used: list[int], fallback: bool) -> None:
    """어떤 해설을 찾았고 그중 몇 번 근거를 썼는지 남긴다. 질문과 답 내용은 대화 기록에 있으므로 넣지 않는다."""
    logger.info(
        "Health RAG answer: entries=%s used=%s fallback=%s knowledge=%s",
        ",".join(f"{match.entry.id}:{match.basis}" for match in matches) or "-",
        ",".join(str(number) for number in used) or "-",
        str(fallback).lower(),
        health_knowledge_version(),
    )


def load_health_retriever_if_enabled() -> None:
    """기동 때 부른다. 설정이 켜져 있으면 해설을 미리 읽어 잘못된 상태를 요청 전에 드러낸다.

    에이전트 모드면 그래프와 모델도 미리 만든다. 설정이 잘못됐으면(agent인데 해설 RAG 꺼짐, 키 없음,
    에이전트를 지원하지 않는 제공자) 여기서 멈춘다.
    """
    settings = ChatbotSettings.load()
    if settings.health_rag_enabled:
        health_retriever()
    if settings.chatbot_mode == "agent":
        from app.chatbot.agent.graph import agent_graph
        from app.chatbot.agent.models import chat_model

        agent_graph()
        chat_model()
        # 하이브리드 색인(해설 조각 벡터)을 첫 질문 전에 만든다. 임베딩 서비스가 없으면 규칙만으로 시작한다.
        agent_health_retriever()


def find_health_evidence(animal, question: str) -> tuple[list[HealthMatch], list]:
    """공고 특이사항과 질문으로 해설을 찾고 번호 붙은 근거로 묶는다."""
    matches = health_retriever().search(animal.specialMark, question, animal.species)
    return matches, build_evidence(matches, animal.species)


def chatbot_response(provider_name: str, answer: str, sources: list, cards: list) -> dict:
    return {
        "answer": answer,
        "safetyNotice": SAFETY_NOTICE,
        "provider": provider_name,
        "sources": sources,
        "healthCards": cards,
    }


async def answer_fixed(provider, req, matches, evidence, timeout: float | None = None) -> dict:
    """고정 흐름: 모델을 한 번 불러 답하고 검사한다. 에이전트가 실패했을 때의 비상 경로로도 쓴다.

    evidence가 None이면 건강 해설 RAG를 끈 기존 동작, 빈 목록이면 켰지만 근거를 못 찾은 경우다.
    """
    animal = req.animalContext
    try:
        prompt = build_prompt(animal, req.recentMessages, req.question, evidence)
        answer = await provider.generate_answer(prompt, json_output=bool(evidence), timeout=timeout)
    except ChatbotProviderConfigurationError as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc
    except ChatbotProviderTruncatedError as exc:
        # 근거 답변이 잘렸으면 오류 대신 대체 답변을 보낸다.
        if not evidence:
            raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc)) from exc
        answer = None
    except ChatbotProviderUpstreamError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=str(exc),
        ) from exc

    sources = []
    cards = []
    if evidence:
        checked = check_answer(answer, evidence, expect_json=True) if answer is not None else None
        answer = checked.answer if checked else FALLBACK_ANSWER
        sources = sources_for(checked.used, evidence) if checked else []
        cards = health_cards(matches, animal.species)
        log_health_answer(matches, checked.used if checked else [], checked.fallback if checked else True)
        answer = strip_citations(answer)
    elif evidence is not None:
        # 설정은 켜졌지만 근거를 찾지 못한 답도 의학 표현을 검사한다.
        plain = check_plain_answer(answer)
        answer = plain.answer
        log_health_answer([], [], plain.fallback)

    return chatbot_response(provider.name, answer, sources, cards)


def provider_or_500():
    try:
        return get_chatbot_provider()
    except ChatbotProviderConfigurationError as exc:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(exc)) from exc


class ChatbotService:
    async def create_message(self, req):
        settings = ChatbotSettings.load()
        if settings.chatbot_mode == "agent":
            # 에이전트 모드에서만 LangChain을 불러 고정 흐름의 기동 메모리를 늘리지 않는다.
            from app.chatbot.agent.service import create_agent_message

            return await create_agent_message(req)

        matches = []
        evidence = None
        if settings.health_rag_enabled:
            matches, evidence = find_health_evidence(req.animalContext, req.question)
        return await answer_fixed(provider_or_500(), req, matches, evidence)
