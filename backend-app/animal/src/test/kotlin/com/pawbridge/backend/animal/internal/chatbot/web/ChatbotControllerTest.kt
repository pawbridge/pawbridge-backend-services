package com.pawbridge.backend.animal.internal.chatbot.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageResponse
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotAnimalNotFoundException
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

class ChatbotControllerTest {
    private val service = mockk<ChatbotService>()
    private val json = ObjectMapper()
    private val validator = LocalValidatorFactoryBean()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        validator.afterPropertiesSet()
        mvc = MockMvcBuilders.standaloneSetup(ChatbotController(service))
            .setControllerAdvice(ChatbotExceptionHandler())
            .setValidator(validator)
            .setMessageConverters(MappingJackson2HttpMessageConverter(json))
            .build()
    }

    @AfterEach
    fun close() {
        validator.close()
    }

    @Test
    fun `익명 요청에 유효한 본문과 UUID가 아닌 세션 문자열이 있을 때__전송하면__원래 요청을 넘기고 필드 여섯 개의 응답을 그대로 돌려준다`() {
        // given
        val input = ChatbotMessageRequest("not-a-uuid", "입양 문의")
        val result = ChatbotMessageResponse("owned-session", "답변", "주의", "fixture")

        every { service.createMessage(any(), any(), any(), any()) } returns result

        // when
        val body = json.readTree(
            mvc.perform(
                post("/api/v1/animals/41/chat/messages")
                    .header("X-User-Id", "99")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(input)),
            ).andExpect(status().isOk()).andReturn().response.contentAsByteArray,
        )

        // then
        assertEquals(json.readTree(json.writeValueAsBytes(result)), body)
        // 기존 네 칸 + 건강 해설 RAG의 sources, healthCards(없으면 빈 목록)
        assertEquals(6, body.size())
        assertEquals(0, body.get("sources").size())
        assertEquals(0, body.get("healthCards").size())
        assertFalse(body.has("data"))
        assertFalse(body.has("code"))
        verify(exactly = 1) { service.createMessage(41L, input, any(), any()) }
        confirmVerified(service)
    }

    @ParameterizedTest
    @MethodSource("invalid")
    fun `원래 본문 제약을 어긴 요청일 때__전송하면__서비스 효과보다 먼저 검증 400을 낸다`(
        request: ChatbotMessageRequest,
        message: String,
    ) {
        // given
        // when
        val body = mvc.perform(
            post("/api/v1/animals/41/chat/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request)),
        ).andExpect(status().isBadRequest()).andReturn().response.contentAsByteArray

        // then
        error(body, 400, message)
        confirmVerified(service)
    }

    @Test
    fun `깨진 JSON일 때__전송하면__서비스 호출 없이 원래의 잘못된 본문 메시지를 유지한다`() {
        // given
        // when
        val body = mvc.perform(
            post("/api/v1/animals/41/chat/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{broken"),
        ).andExpect(status().isBadRequest()).andReturn().response.contentAsByteArray

        // then
        error(body, 400, "요청 본문 형식이 올바르지 않습니다.")
        confirmVerified(service)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `업무 오류나 상류 오류나 저장소 오류가 날 때__전송하면__상태와 응답 형식과 정리된 서버 메시지를 유지한다`(
        failure: RuntimeException,
        statusCode: Int,
        message: String,
    ) {
        // given
        every { service.createMessage(any(), any(), any(), any()) } throws failure

        // when
        val body = mvc.perform(
            post("/api/v1/animals/41/chat/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"입양 문의\"}"),
        ).andExpect(status().`is`(statusCode)).andReturn().response.contentAsByteArray

        // then
        error(body, statusCode, message)
        assertFalse(String(body, StandardCharsets.UTF_8).contains("private"))
        verify(exactly = 1) { service.createMessage(41L, any(), any(), any()) }
    }

    @Test
    fun `로컬 가드 결과일 때__전송하면__한도 429 대신 응답 200을 그대로 돌려준다`() {
        // given
        val result = ChatbotMessageResponse("owned-session", "보호동물 관련 질문을 입력해주세요.", "고정 주의문", "guard")

        every { service.createMessage(any(), any(), any(), any()) } returns result

        // when
        val body = json.readTree(
            mvc.perform(
                post("/api/v1/animals/41/chat/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"주식 질문\"}"),
            ).andExpect(status().isOk()).andReturn().response.contentAsByteArray,
        )

        // then
        assertEquals(json.readTree(json.writeValueAsBytes(result)), body)
        // 기존 네 칸 + 건강 해설 RAG의 sources, healthCards(없으면 빈 목록)
        assertEquals(6, body.size())
        assertEquals(0, body.get("sources").size())
        assertEquals(0, body.get("healthCards").size())
        verify(exactly = 1) { service.createMessage(41L, any(), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/api/animals/41/chat/messages",
            "/api/v1/animals/41/chat/message",
            "/api/v1/animals/41/chat/messages/admin",
        ],
    )
    fun `정규 경로가 아닐 때__전송하면__챗봇 컨트롤러 핸들러가 없다`(path: String) {
        // given
        // when
        val result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"입양 문의\"}"))

        // then
        result.andExpect(status().isNotFound())
        confirmVerified(service)
    }

    private fun error(bytes: ByteArray, code: Int, message: String) {
        val body = json.readTree(bytes)
        assertEquals(3, body.size())
        assertEquals(code, body.get("code").intValue())
        assertEquals(message, body.get("message").asText())
        assertTrue(body.get("data").isNull())
    }

    companion object {
        @JvmStatic
        fun invalid(): Stream<Arguments> = Stream.of(
            Arguments.of(ChatbotMessageRequest(null, null), "question: question must not be blank"),
            Arguments.of(ChatbotMessageRequest(null, "   "), "question: question must not be blank"),
            Arguments.of(
                ChatbotMessageRequest(null, "가".repeat(501)),
                "question: question must be 500 characters or less",
            ),
            Arguments.of(
                ChatbotMessageRequest("s".repeat(37), "입양 문의"),
                "sessionId: sessionId must be 36 characters or less",
            ),
        )

        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(ChatbotAnimalNotFoundException(), 404, "동물을 찾을 수 없습니다"),
            Arguments.of(IllegalArgumentException("Invalid chatbot session"), 400, "Invalid chatbot session"),
            Arguments.of(ChatbotRateLimitExceededException(), 429, "챗봇 사용량이 많습니다. 잠시 후 다시 시도해주세요."),
            Arguments.of(
                DataAccessResourceFailureException("private SQL and question"),
                500,
                "데이터베이스 오류가 발생했습니다.",
            ),
            Arguments.of(IllegalStateException("private upstream and key"), 500, "서버 내부 오류가 발생했습니다."),
        )
    }
}
