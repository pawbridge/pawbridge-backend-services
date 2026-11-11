import os
import unittest
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage

from app.chatbot.agent import similar
from app.chatbot.health.retriever import RuleHealthRetriever
from app.entrypoints.chatbot_api import app
from tests.test_chatbot_agent import AGENT_ENV, COUGH, HEARTWORM, FixedProvider, ScriptedChatModel, reply, usage

BACKEND = "http://monolith.example.invalid:8080"
CARDS = [
    {
        "id": 7,
        "apmsNoticeNo": "경기-양평-2025-00429",
        "species": "DOG",
        "breed": "믹스견",
        "gender": "MALE",
        "birthYear": 2023,
        "color": "갈색",
        "weight": "5(Kg)",
        "specialMark": "피부병 치료 중",
        "status": "PROTECT",
        "noticeEndDate": "2026-11-20",
        "shelterName": "가상 보호소",
    },
    {"id": 8, "apmsNoticeNo": "경기-양평-2026-00001", "species": "DOG", "breed": "푸들", "gender": "FEMALE"},
]


def similar_call(count=None):
    args = {} if count is None else {"count": count}
    return AIMessage(content="", tool_calls=[{"name": "find_similar_animals", "args": args, "id": "call-similar"}],
                     usage_metadata=usage(), response_metadata={"finish_reason": "STOP"})


class SimilarAnimalsTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)
        self.payload = {
            "animalId": 41,
            "animalContext": {"species": "DOG", "specialMark": "순함"},
            "shelterContext": {"shelterName": "행복 보호소"},
            "recentMessages": [],
            "question": "이 아이랑 비슷한 다른 아이도 있나요?",
        }
        retriever = RuleHealthRetriever([HEARTWORM, COUGH])
        for target in ("app.chatbot.service.health_retriever", "app.chatbot.agent.service.health_retriever"):
            patcher = patch(target, return_value=retriever)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.requests = []
        self.backend_status = 200
        self.backend_body = CARDS

    def backend(self, request):
        self.requests.append(request)
        if isinstance(self.backend_body, Exception):
            raise self.backend_body
        return httpx.Response(self.backend_status, json=self.backend_body)

    def post(self, model, env=None):
        real_client = httpx.AsyncClient

        def client(**kwargs):
            return real_client(transport=httpx.MockTransport(self.backend), **kwargs)

        environment = {**AGENT_ENV, "CHATBOT_BACKEND_BASE_URL": BACKEND, **(env or {})}
        with patch.dict(os.environ, environment), \
                patch("app.chatbot.service.get_chatbot_provider", return_value=FixedProvider()), \
                patch("app.chatbot.agent.service.chat_model", return_value=model), \
                patch("app.chatbot.agent.similar.httpx.AsyncClient", side_effect=client):
            return self.client.post("/internal/chatbot/messages", headers={"X-Internal-Api-Key": "k"}, json=self.payload)

    def tool_result(self, model):
        message = model.calls[1]["messages"][-1]
        self.assertEqual(message.type, "tool")
        return message.content

    def test_tool_returns_backend_cards_and_answer_names_one(self):
        answer = "사진이 비슷한 아이로 공고번호 경기-양평-2025-00429 믹스견 수컷이 있어요."
        model = ScriptedChatModel(turns=[similar_call(), reply(answer)])
        body = self.post(model).json()
        self.assertEqual(body["answer"], answer)
        self.assertEqual(model.calls[0]["tools"], ["search_health", "adoption_guide", "find_similar_animals"])
        self.assertEqual([str(request.url) for request in self.requests], [f"{BACKEND}/api/v1/animals/41/similar"])
        result = self.tool_result(model)
        self.assertIn("- 공고번호 경기-양평-2025-00429: 개, 믹스견, 수컷, 2023년생, 털색 갈색, 체중 5(Kg), 가상 보호소, 공고 종료 2026-11-20", result)
        self.assertIn("- 공고번호 경기-양평-2026-00001: 개, 푸들, 암컷", result)
        # 특이사항은 넘기지 않는다(병명이 답에 섞이면 의학 표현 검사에 걸린다).
        self.assertNotIn("피부병", result)

    def test_made_up_notice_number_is_replaced(self):
        model = ScriptedChatModel(turns=[similar_call(), reply("공고번호 경기-양평-2025-99999 아이가 비슷해요.")])
        self.assertEqual(self.post(model).json()["answer"], similar.SIMILAR_FALLBACK)

    def test_notice_number_without_tool_call_is_replaced(self):
        # 도구를 부르지 않고 공고번호를 지어내도 걸린다.
        model = ScriptedChatModel(turns=[reply("공고번호 경기-양평-2025-00429 아이가 비슷해요.")])
        self.assertEqual(self.post(model).json()["answer"], similar.SIMILAR_FALLBACK)

    def test_count_picks_how_many_and_default_is_three(self):
        self.backend_body = [dict(CARDS[1], id=10 + index, apmsNoticeNo=f"경기-양평-2026-0010{index}") for index in range(6)]
        for count, expected in ((None, similar.DEFAULT_ANIMALS), (1, 1), (similar.MAX_ANIMALS, similar.MAX_ANIMALS)):
            with self.subTest(count=count):
                model = ScriptedChatModel(turns=[similar_call(count), reply("비슷한 아이들이 있어요.")])
                self.post(model)
                self.assertEqual(self.tool_result(model).count("- 공고번호"), expected)

    def test_count_over_limit_is_sent_back_as_tool_error(self):
        model = ScriptedChatModel(turns=[similar_call(similar.MAX_ANIMALS + 1), reply("비슷한 아이들이 있어요.")])
        self.post(model)
        self.assertEqual(model.calls[1]["messages"][-1].status, "error")
        self.assertEqual(self.requests, [])

    def test_listed_but_unshown_card_does_not_allow_its_number(self):
        # 모델에게 보여 주지 않은 동물의 번호는 허용하지 않는다(기본 3마리 밖의 다섯째).
        self.backend_body = [dict(CARDS[1], id=10 + index, apmsNoticeNo=f"경기-양평-2026-0010{index}") for index in range(6)]
        model = ScriptedChatModel(turns=[similar_call(), reply("공고번호 경기-양평-2026-00104 아이가 있어요.")])
        self.assertEqual(self.post(model).json()["answer"], similar.SIMILAR_FALLBACK)

    def test_empty_result_tells_model_there_is_none(self):
        self.backend_body = []
        model = ScriptedChatModel(turns=[similar_call(), reply("지금은 비슷한 아이가 없어요.")])
        body = self.post(model).json()
        self.assertEqual(self.tool_result(model), similar.NOTHING_SIMILAR)
        self.assertEqual(body["answer"], "지금은 비슷한 아이가 없어요.")

    def test_backend_failure_is_a_tool_message_not_an_error(self):
        for status, body in ((503, {"message": "unavailable"}), (200, {"not": "a list"})):
            with self.subTest(status=status):
                self.backend_status, self.backend_body = status, body
                model = ScriptedChatModel(turns=[similar_call(), reply("지금은 추천을 불러오지 못했어요.")])
                with self.assertLogs("uvicorn.error", level="INFO") as logs:
                    response = self.post(model)
                self.assertEqual(response.status_code, 200)
                self.assertEqual(self.tool_result(model), similar.UNAVAILABLE)
                line = next(message for message in logs.output if "Chatbot agent:" in message)
                self.assertIn("path=agent", line)
                self.assertIn("tool_errors=0", line)
                # 주소는 로그에 남기지 않는다.
                self.assertFalse(any("monolith.example.invalid" in message for message in logs.output))

    def test_connection_error_is_a_tool_message(self):
        self.backend_body = httpx.ConnectError("refused")
        model = ScriptedChatModel(turns=[similar_call(), reply("지금은 추천을 불러오지 못했어요.")])
        self.post(model)
        self.assertEqual(self.tool_result(model), similar.UNAVAILABLE)

    def test_without_animal_id_backend_is_not_called(self):
        del self.payload["animalId"]
        model = ScriptedChatModel(turns=[similar_call(), reply("공고 목록에서 찾아보세요.")])
        self.post(model)
        self.assertEqual(self.tool_result(model), similar.NO_ANIMAL)
        self.assertEqual(self.requests, [])

    def test_without_backend_url_backend_is_not_called(self):
        model = ScriptedChatModel(turns=[similar_call(), reply("잠시 뒤 다시 물어봐 주세요.")])
        self.post(model, env={"CHATBOT_BACKEND_BASE_URL": ""})
        self.assertEqual(self.tool_result(model), similar.UNAVAILABLE)
        self.assertEqual(self.requests, [])

    def test_animal_id_must_be_positive(self):
        self.payload["animalId"] = 0
        with patch.dict(os.environ, AGENT_ENV):
            response = self.client.post("/internal/chatbot/messages", headers={"X-Internal-Api-Key": "k"}, json=self.payload)
        self.assertEqual(response.status_code, 422)


class GeminiToolDeclarationTest(unittest.TestCase):
    def test_tool_without_arguments_converts_to_gemini_declaration(self):
        # 입력이 없는 도구도 Gemini 함수 선언으로 바뀌어야 한다(바뀌지 않으면 운영에서 첫 호출이 실패한다).
        try:
            from langchain_google_genai._function_utils import convert_to_genai_function_declarations
        except ImportError:
            self.skipTest("langchain_google_genai 없음")
        from app.chatbot.agent.graph import _langchain_tool
        from app.chatbot.agent.tools import TOOLS

        tools = [_langchain_tool(spec, function) for spec, function in TOOLS.values()]
        declarations = [item for tool in convert_to_genai_function_declarations(tools) for item in tool.function_declarations]
        self.assertEqual([item.name for item in declarations], ["search_health", "adoption_guide", "find_similar_animals"])
        # 입력이 없는 OBJECT는 Gemini가 거부할 수 있다. 모든 도구에 입력이 하나 이상 있어야 한다.
        for item in declarations:
            self.assertTrue(item.parameters.properties, item.name)


if __name__ == "__main__":
    unittest.main()
