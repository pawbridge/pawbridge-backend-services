package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

// 건강 해설 RAG 답변이 근거로 쓴 출처. Python이 해설 파일에서 꺼내 준 값을 그대로 전달한다.
@JvmRecord
data class ChatbotHealthSource(
    val org: String?,
    val title: String?,
    val url: String?,
    val checkedAt: String?,
    val language: String?,
)
