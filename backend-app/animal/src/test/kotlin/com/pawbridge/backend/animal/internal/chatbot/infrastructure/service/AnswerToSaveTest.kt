package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageResponse
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotHealthSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnswerToSaveTest {
    private val source = ChatbotHealthSource("기관", "문서", "https://example.org/a", "2026-11-06", "ko")

    private fun rag(answer: String) = PythonChatbotMessageResponse(answer, "주의", "gemini", listOf(source), emptyList())

    private fun plain(answer: String) = PythonChatbotMessageResponse(answer, "주의", "gemini")

    @Test
    fun `출처가 붙은 건강 해설 답일 때__저장하면__근거 번호만 지운다`() {
        assertEquals("운동을 제한해야 합니다. 보호소에 물어보세요.", answerToSave(rag("운동을 제한해야 합니다 [2]. 보호소에 물어보세요.")))
    }

    @Test
    fun `출처가 없는 일반 답일 때__저장하면__목록 번호와 줄바꿈을 그대로 둔다`() {
        val answer = "확인할 것:\n[1] 건강\n[2] 성격"
        assertEquals(answer, answerToSave(plain(answer)))
    }

    @Test
    fun `긴 숫자나 가드 표시가 있을 때__저장하면__남긴다`() {
        assertEquals("[2023]년 [PHONE] 문의.", answerToSave(rag("[2023]년 [PHONE] 문의 [1].")))
    }

    @Test
    fun `번호만 있는 답일 때__저장하면__빈 글 대신 원문을 둔다`() {
        assertEquals("[1]", answerToSave(rag("[1]")))
    }
}
