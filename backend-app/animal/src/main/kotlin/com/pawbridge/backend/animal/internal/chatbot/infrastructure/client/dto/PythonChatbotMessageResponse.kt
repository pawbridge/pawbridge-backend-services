package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotHealthCard
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotHealthSource

@JvmRecord
data class PythonChatbotMessageResponse(
    val answer: String?,
    val safetyNotice: String?,
    val provider: String?,
    // 건강 해설 RAG를 켰을 때만 오는 칸. 이전 Python 응답에는 없으므로 비어 있을 수 있다.
    val sources: List<ChatbotHealthSource>? = null,
    val healthCards: List<ChatbotHealthCard>? = null,
)
