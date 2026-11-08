package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageResponse
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotController
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotExceptionHandler
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.animal-chatbot.enabled=true", "pawbridge.animal-query.backend=postgresql"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ChatbotController::class,
        ChatbotExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalChatbotHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var chatbot: ChatbotService

    @ParameterizedTest
    @ValueSource(strings = ["anonymous", "unrelated-invalid-bearer"])
    fun `원래 공개 경로__회원 신원 없이 게시하면__순수 챗봇 응답을 돌려준다`(
        authorization: String,
    ) {
        // given
        val input = ChatbotMessageRequest("not-a-uuid", "입양 문의")
        val command = command("POST", "/api/v1/animals/41/chat/messages")
            .header("X-User-Id", "99")
            .content(mapper.writeValueAsBytes(input))
        if (authorization == "unrelated-invalid-bearer") {
            command.header("Authorization", "Bearer synthetic-invalid-token")
        }

        every { chatbot.createMessage(any(), any(), any(), any()) } returns
            ChatbotMessageResponse("owned-session", "안내 답변", "안전 주의", "synthetic")

        // when
        val response = mvc.perform(command).andExpect(status().isOk()).andReturn().response

        // then
        val json = mapper.readTree(response.contentAsByteArray)
        // 기존 네 칸 + 건강 해설 RAG의 sources, healthCards(없으면 빈 목록)
        assertThat(json.size()).isEqualTo(6)
        assertThat(json.path("sources").size()).isEqualTo(0)
        assertThat(json.path("healthCards").size()).isEqualTo(0)
        assertThat(json.path("sessionId").asText()).isEqualTo("owned-session")
        assertThat(json.path("answer").asText()).isEqualTo("안내 답변")
        assertThat(json.path("safetyNotice").asText()).isEqualTo("안전 주의")
        assertThat(json.path("provider").asText()).isEqualTo("synthetic")
        assertThat(json.has("data")).isFalse()
        assertThat(json.has("code")).isFalse()
        verify(exactly = 1) { chatbot.createMessage(41L, input, any(), any()) }
        confirmVerified(chatbot)
    }

    @Test
    fun `잘못된 원래 질문__익명으로 게시하면__업무 처리 전에 범위 지정 검증 봉투를 돌려준다`() {
        // given
        val request = command("POST", "/api/v1/animals/41/chat/messages").content("{\"question\":\"   \"}")

        // when
        val response = mvc.perform(request).andExpect(status().isBadRequest()).andReturn().response

        // then
        val json = mapper.readTree(response.contentAsByteArray)
        assertThat(json.size()).isEqualTo(3)
        assertThat(json.path("code").asInt()).isEqualTo(400)
        assertThat(json.path("message").asText()).isEqualTo("question: question must not be blank")
        assertThat(json.get("data").isNull()).isTrue()
        confirmVerified(chatbot)
    }

    @Test
    fun `깨진 JSON__익명으로 게시하면__업무 처리 전에 범위 지정 본문 오류를 유지한다`() {
        // given
        val request = command("POST", "/api/v1/animals/41/chat/messages").content("{broken")

        // when
        val response = mvc.perform(request).andExpect(status().isBadRequest()).andReturn().response

        // then
        val json = mapper.readTree(response.contentAsByteArray)
        assertThat(json.path("code").asInt()).isEqualTo(400)
        assertThat(json.path("message").asText()).isEqualTo("요청 본문 형식이 올바르지 않습니다.")
        assertThat(json.get("data").isNull()).isTrue()
        confirmVerified(chatbot)
    }

    companion object {
        fun command(method: String, path: String): MockHttpServletRequestBuilder {
            return request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON)
        }
    }
}
