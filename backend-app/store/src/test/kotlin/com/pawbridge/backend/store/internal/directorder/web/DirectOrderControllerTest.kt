package com.pawbridge.backend.store.internal.directorder.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionSystemException
import java.sql.BatchUpdateException
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.stream.Stream

class DirectOrderControllerTest {
    private val orders: DirectOrders = mockk<DirectOrders>()
    private val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val command = DirectOrders.Command(80L, 2, "fixture receiver", "fixture phone", "fixture address", "fixture message")
    private val request =
        "{\"skuId\":80,\"quantity\":2,\"receiverName\":\"fixture receiver\",\"receiverPhone\":\"fixture phone\"," +
            "\"deliveryAddress\":\"fixture address\",\"deliveryMessage\":\"fixture message\",\"userId\":99}"
    private val response = DirectOrders.OrderDetails(
        9007199254740993L, "fixture-uuid", 7L, 9007199254740993L, "PENDING", "READY",
        "fixture receiver", "fixture phone", "fixture address", "fixture message",
        LocalDateTime.of(2026, 10, 20, 22, 0, 1, 123456789),
        listOf(DirectOrders.Item("fixture product", "CURRENT-80", 9007199254740993L, 2)),
    )
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(DirectOrderController(orders), OrderReadController(orders))
            .setControllerAdvice(DirectOrderExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(MappingJackson2HttpMessageConverter(json)).build()
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    private fun current(member: Any) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(member, "unused", listOf<GrantedAuthority>())
    }

    private fun fullResponse(bytes: ByteArray) {
        val node = json.readTree(bytes)
        assertEquals(12, node.size())
        assertFalse(node.has("data"))
        assertEquals(json.readTree(json.writeValueAsBytes(response)), node)
        assertEquals(4, node.get("items").get(0).size())
        assertEquals(9007199254740993L, node.get("orderId").longValue())
        assertEquals(9007199254740993L, node.get("items").get(0).get("price").longValue())
        assertEquals("2026-10-20T22:00:01.123456789", node.get("createdAt").asText())
    }

    @Test
    fun `검증된 Long 회원과 위조된 사용자 출처가 있을 때__구매하면__검증된 회원과 전체 요청을 넘기고 원본 응답을 돌려준다`() {
        // given
        current(7L)

        every { orders.create(any(), any()) } returns response

        // when
        val bytes =
            mvc.perform(
                post("/api/v1/orders/direct").param("userId", "99").header("X-User-Id", "99")
                    .contentType(MediaType.APPLICATION_JSON).content(request),
            ).andExpect(status().isCreated()).andReturn().response.contentAsByteArray

        // then
        fullResponse(bytes)
        verify(exactly = 1) { orders.create(7, command) }
        confirmVerified(orders)
    }

    @Test
    fun `검증된 Long 회원과 위조된 사용자 쿼리가 있을 때__주문을 조회하면__검증된 회원만 쓰고 전체 주문을 돌려준다`() {
        // given
        current(7L)

        every { orders.get(any(), any()) } returns response

        // when
        val bytes = mvc.perform(get("/api/v1/orders/9007199254740993").param("userId", "99").header("X-User-Id", "99"))
            .andExpect(status().isOk()).andReturn().response.contentAsByteArray

        // then
        fullResponse(bytes)
        verify(exactly = 1) { orders.get(7, 9007199254740993L) }
        confirmVerified(orders)
    }

    @ParameterizedTest
    @MethodSource("invalidPrincipals")
    fun `주체가 없거나 Long이 아니고 사용자가 위조됐을 때__접근하면__애플리케이션 호출 전에 401을 돌려준다`(
        kind: String,
        method: String,
    ) {
        // given
        if (kind == "text") {
            current("99")
        }
        val call = if (method == "POST") {
            post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request)
        } else {
            get("/api/v1/orders/33")
        }

        // when
        val result = mvc.perform(call.param("userId", "99").header("X-User-Id", "99"))

        // then
        result.andExpect(status().isUnauthorized())
            .andExpect(content().string(""))
        confirmVerified(orders)
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    fun `잘못된 SKU나 수량일 때__구매하면__애플리케이션 호출 없이 원본의 필드 값 사유 오류를 유지한다`(
        body: String,
        field: String,
        value: String,
    ) {
        // given
        current(7L)

        // when
        val bytes = mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.message").value("입력값이 올바르지 않습니다."))
            .andExpect(jsonPath("\$.code").value("C001"))
            .andReturn().response.contentAsByteArray

        // then
        val errors = json.readTree(bytes).get("errors")
        assertEquals(1, errors.size())
        val error = errors.get(0)
        assertEquals(3, error.size())
        assertEquals(field, error.get("field").asText())
        assertEquals(value, error.get("value").asText())
        assertFalse(error.get("reason").asText().isBlank())
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["bad", "9223372036854775808"])
    fun `해석할 수 없는 주문 ID일 때__조회하면__애플리케이션 호출 없이 원본의 타입 오류를 유지한다`(orderId: String) {
        // given
        current(7L)

        // when
        val result = mvc.perform(get("/api/v1/orders/{orderId}", orderId))

        // then
        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("orderId"))
            .andExpect(jsonPath("\$.errors[0].value").value(orderId))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))
        confirmVerified(orders)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `행위자나 주문이나 트랜잭션이 실패할 때__구매하면__상태 코드와 정제된 본문을 유지한다`(
        failure: RuntimeException,
        statusCode: Int,
        code: String,
        message: String,
    ) {
        // given
        current(7L)

        every { orders.create(any(), any()) } throws failure

        // when
        val body = mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().`is`(statusCode))
            .andExpect(jsonPath("\$.code").value(code))
            .andExpect(jsonPath("\$.message").value(message))
            .andExpect(jsonPath("\$.errors").isEmpty())
            .andReturn().response.contentAsString

        // then
        assertFalse(body.contains("private"))
        assertFalse(body.contains("fixture"))
        verify(exactly = 1) { orders.create(7, command) }
        confirmVerified(orders)
    }

    @Test
    fun `회원이 접근할 수 없는 주문일 때__조회하면__주문 상세 없이 원본의 잘못된 입력 오류를 유지한다`() {
        // given
        current(7L)

        every { orders.get(any(), any()) } throws IllegalArgumentException("private owner")

        // when
        val result = mvc.perform(get("/api/v1/orders/33"))

        // then
        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C001"))
            .andExpect(jsonPath("\$.errors").isEmpty())
        verify(exactly = 1) { orders.get(7, 33) }
        confirmVerified(orders)
    }

    @ParameterizedTest
    @MethodSource("rollbackCharsetFailures")
    fun `쓰기나 커밋이나 배치에서 롤백 문자셋 제약을 어길 때__구매하면__비공개 SQL 상세 없이 원본 C007을 돌려준다`(
        failure: RuntimeException,
    ) {
        // given
        current(7L)

        every { orders.create(any(), any()) } throws failure

        // when
        val body = mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C007"))
            .andExpect(jsonPath("\$.message").value("일부 이모지 등 지원하지 않는 문자를 제외해 주세요."))
            .andExpect(jsonPath("\$.errors").isEmpty()).andReturn().response.contentAsString

        // then
        assertFalse(body.contains("private"))
        assertFalse(body.contains("ck_rollback_charset_"))
        assertFalse(body.contains("23514"))
        verify(exactly = 1) { orders.create(7, command) }
        confirmVerified(orders)
    }

    @ParameterizedTest
    @MethodSource("unrelatedConstraintFailures")
    fun `다른 제약이나 스키마나 SQLSTATE나 비정형 텍스트일 때__구매하면__비공개 SQL 상세 없이 서버 오류를 유지한다`(
        cause: SQLException,
    ) {
        // given
        current(7L)

        every { orders.create(any(), any()) } throws DataIntegrityViolationException("private fixture", cause)

        // when
        val body = mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
            .andExpect(jsonPath("\$.message").value("서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."))
            .andExpect(jsonPath("\$.errors").isEmpty())
            .andReturn().response.contentAsString

        // then
        assertFalse(body.contains("private"))
        assertFalse(body.contains("ck_rollback_charset_"))
        assertFalse(body.contains("23514"))
        verify(exactly = 1) { orders.create(7, command) }
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/orders/uuid/fixture", "/api/v1/orders/33/payment", "/api/v1/orders/cart/direct"])
    fun `승인되지 않은 인접 흐름일 때__요청하면__직접 주문 핸들러 없이 404를 돌려준다`(path: String) {
        // given
        current(7L)

        // when
        val result = mvc.perform(get(path))

        // then
        result.andExpect(status().isNotFound())
        confirmVerified(orders)
    }

    companion object {
        @JvmStatic
        fun invalidPrincipals(): Stream<Arguments> {
            return Stream.of("missing", "text")
                .flatMap { kind -> Stream.of("POST", "GET").map { method -> Arguments.of(kind, method) } }
        }

        @JvmStatic
        fun invalidRequests(): Stream<Arguments> = Stream.of(
            Arguments.of("{\"skuId\":80,\"quantity\":0}", "quantity", "0"),
            Arguments.of("{\"skuId\":80,\"quantity\":-1}", "quantity", "-1"),
            Arguments.of("{\"skuId\":0,\"quantity\":2}", "skuId", "0"),
            Arguments.of("{\"quantity\":2}", "skuId", ""),
            Arguments.of("{\"skuId\":80}", "quantity", ""),
        )

        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(OrderActorRejectedException(), 403, "C006", "접근 권한이 없습니다."),
            Arguments.of(IllegalArgumentException("private account and shipping"), 400, "C001", "입력값이 올바르지 않습니다."),
            Arguments.of(DataAccessResourceFailureException("private account and shipping"), 500, "C004", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
            Arguments.of(TransactionSystemException("private account and shipping"), 500, "C004", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
        )

        private fun constraint(state: String, schema: String, name: String): PSQLException {
            return PSQLException(
                ServerErrorMessage(
                    "SERROR\u0000C" + state + "\u0000Mprivate_fixture_value\u0000Dprivate_fixture_row\u0000s" +
                        schema + "\u0000n" + name + "\u0000",
                ),
            )
        }

        @JvmStatic
        fun rollbackCharsetFailures(): Stream<Arguments> {
            val violation = constraint("23514", "pawbridge_store", "ck_rollback_charset_orders")
            val batch = BatchUpdateException()
            batch.nextException = violation
            return Stream.of(
                Arguments.of(DataIntegrityViolationException("private fixture", violation)),
                Arguments.of(TransactionSystemException("private commit", violation)),
                Arguments.of(DataIntegrityViolationException("private batch", batch)),
            )
        }

        @JvmStatic
        fun unrelatedConstraintFailures(): Stream<Arguments> = Stream.of(
            Arguments.of(constraint("23514", "pawbridge_store", "business_constraint")),
            Arguments.of(constraint("23514", "other_schema", "ck_rollback_charset_orders")),
            Arguments.of(constraint("23505", "pawbridge_store", "ck_rollback_charset_orders")),
            Arguments.of(SQLException("ck_rollback_charset_orders private_fixture_value", "23514")),
        )
    }
}
