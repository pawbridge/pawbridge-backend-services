package com.pawbridge.backend.animal.internal.chatbot.web

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageResponse
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/animals/{animalId}/chat/messages")
class ChatbotController(
    private val chatbotService: ChatbotService,
) {

    /**
     * 공개 경로다. 보안 체인이 유효한 로그인 토큰만 회원 번호로 넘기고, 토큰이 없거나 틀리면 비로그인(null)으로 둔다.
     */
    @PostMapping
    fun createMessage(
        @PathVariable("animalId") animalId: Long,
        @Valid @RequestBody request: ChatbotMessageRequest,
        @AuthenticationPrincipal memberId: Long?,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<ChatbotMessageResponse> {
        return ResponseEntity.ok(chatbotService.createMessage(animalId, request, memberId, httpRequest, httpResponse))
    }
}
