package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

@JvmRecord
data class ChatbotMessageResponse(
    val sessionId: String?,
    val answer: String?,
    val safetyNotice: String?,
    val provider: String?,
    // 건강 해설 RAG의 출처와 검수된 해설. 없으면 빈 목록이다.
    val sources: List<ChatbotHealthSource> = emptyList(),
    val healthCards: List<ChatbotHealthCard> = emptyList(),
)
