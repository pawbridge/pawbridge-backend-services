package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.AnimalChatbotContext
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotRecentMessage
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ShelterChatbotContext

@JvmRecord
data class PythonChatbotMessageRequest(
    // 지금 공고 동물 id. Python 상담 에이전트의 비슷한 동물 도구가 이 id로 추천 API를 부른다.
    val animalId: Long?,
    val animalContext: AnimalChatbotContext?,
    val shelterContext: ShelterChatbotContext?,
    val recentMessages: List<ChatbotRecentMessage>?,
    val question: String?,
)
