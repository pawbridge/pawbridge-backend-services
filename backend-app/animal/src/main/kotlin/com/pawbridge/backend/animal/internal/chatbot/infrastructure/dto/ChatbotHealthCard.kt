package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

// 사람이 검수한 건강 해설 원문("검수된 해설 보기"에 표시).
@JvmRecord
data class ChatbotHealthCard(
    val id: String?,
    val title: String?,
    val summary: List<String>?,
    val cautions: List<String>?,
    val askShelter: List<String>?,
)
