package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.AnimalChatbotContext
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotRecentMessage
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ShelterChatbotContext

@JvmRecord
data class PythonChatbotMessageRequest(
    val animalContext: AnimalChatbotContext?,
    val shelterContext: ShelterChatbotContext?,
    val recentMessages: List<ChatbotRecentMessage>?,
    val question: String?,
)
