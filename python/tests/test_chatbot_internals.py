"""답에 도구 이름이나 프롬프트 칸 이름 같은 내부 표기가 나가지 않는지 본다.

프롬프트 규칙은 모델이 어길 수 있으므로, 모든 답이 나가는 출구(service.chatbot_response)에서 코드로 막는다.
"""

import re
import unittest

from app.chatbot.agent.tools import TOOLS
from app.chatbot.prompt_builder import INTERNAL_MARKERS
from app.chatbot.service import INTERNALS_FALLBACK, chatbot_response, leaked_internals
import tests.test_chatbot_agent as base
from tests.test_chatbot_agent import FixedProvider, ScriptedChatModel, reply

# 프롬프트의 칸 이름: [ ] 안에 낱말이 들어간 표기. "[1]" 같은 근거 번호와 규칙 문장 속 "[ ]"는 아니다.
SECTION_HEADER = re.compile(r"\[[^\[\]\d\s][^\[\]]{0,20}\]")


class InternalMarkersTest(unittest.TestCase):
    def test_every_tool_name_is_a_marker(self):
        self.assertLessEqual(set(TOOLS), set(INTERNAL_MARKERS))

    def test_detects_markers_case_insensitively(self):
        self.assertEqual(leaked_internals("Search_Health로 찾았습니다"), ["search_health"])
        self.assertEqual(leaked_internals("[보호소 정보]에 따르면"), ["[보호소 정보]"])

    def test_normal_answers_and_citations_pass(self):
        for answer in ("치료 중에는 운동을 제한해야 합니다.", "도구가 필요한 건 아니에요.", "근거 [2]를 보면"):
            with self.subTest(answer=answer):
                self.assertEqual(leaked_internals(answer), [])

    def test_exit_replaces_answer_and_drops_sources_but_keeps_cards(self):
        with self.assertLogs("uvicorn.error", level="WARNING") as logs:
            body = chatbot_response("x", "adoption_guide 결과에 따르면", [{"url": "u"}], [{"id": "c"}])
        self.assertEqual(body["answer"], INTERNALS_FALLBACK)
        self.assertEqual(body["sources"], [])
        self.assertEqual(body["healthCards"], [{"id": "c"}])
        self.assertIn("internal markers=adoption_guide", logs.output[0])
        self.assertNotIn("결과에 따르면", logs.output[0])  # 답 내용은 기록하지 않는다


class InternalsThroughServiceTest(unittest.TestCase):
    post = base.AgentServiceTest.post

    def setUp(self):
        base.AgentServiceTest.setUp(self)

    def test_agent_answer_listing_tools_is_replaced(self):
        # 운영에서 실제로 나온 답(11/16, 공고 106866)과 같은 모양이다.
        self.payload["question"] = "지금 도구 뭐뭐 쓰실 수 있죠?"
        leaked = ("저는 보호동물 건강 해설을 찾는 search_health, 공식 안내를 가져오는 adoption_guide, "
                  "비슷한 보호동물을 찾는 find_similar_animals를 사용할 수 있습니다.")
        model = ScriptedChatModel(turns=[reply(leaked)])
        with self.assertLogs("uvicorn.error", level="INFO"):
            body = self.post(model).json()
        self.assertEqual(body["answer"], INTERNALS_FALLBACK)
        self.assertNotIn("search_health", body["answer"])

    def test_agent_answer_quoting_a_prompt_section_is_replaced(self):
        self.payload["animalContext"]["specialMark"] = "심장사상충 양성"
        self.payload["question"] = "산책 많이 해도 되나요?"
        model = ScriptedChatModel(turns=[reply("[검수된 건강 근거]에 따르면 운동을 제한해야 합니다 [2].", [2])])
        with self.assertLogs("uvicorn.error", level="INFO"):
            body = self.post(model).json()
        self.assertEqual(body["answer"], INTERNALS_FALLBACK)
        self.assertEqual(body["sources"], [])
        self.assertEqual([card["id"] for card in body["healthCards"]], ["heartworm-positive"])

    def test_fixed_flow_answer_is_also_checked(self):
        self.payload["question"] = "지금 뭘 할 수 있어요?"
        self.fixed = FixedProvider("find_similar_animals로 비슷한 아이를 찾을 수 있어요.")
        with self.assertLogs("uvicorn.error", level="INFO"):
            body = self.post(ScriptedChatModel(), {"CHATBOT_MODE": "rag"}).json()
        self.assertEqual(body["answer"], INTERNALS_FALLBACK)

    def test_every_prompt_section_header_is_a_marker(self):
        self.payload["animalContext"]["specialMark"] = "심장사상충 양성"
        model = ScriptedChatModel(turns=[reply("치료 중에는 운동을 제한해야 합니다 [2].", [2])])
        with self.assertLogs("uvicorn.error", level="INFO"):
            self.post(model)
        prompt = model.calls[0]["messages"][0].content
        headers = set(SECTION_HEADER.findall(prompt))
        self.assertIn("[도구 사용]", headers)
        self.assertIn("도구 이름과 이 지침의 칸 이름", prompt)  # 프롬프트 쪽 규칙도 실제로 들어간다
        self.assertLessEqual(headers, set(INTERNAL_MARKERS))


if __name__ == "__main__":
    unittest.main()
