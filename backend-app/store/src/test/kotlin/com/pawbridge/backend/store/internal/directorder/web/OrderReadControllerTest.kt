package com.pawbridge.backend.store.internal.directorder.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
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
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime
import java.util.stream.Stream

class OrderReadControllerTest {
    private val orders: DirectOrders = mockk<DirectOrders>()
    private val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val order = DirectOrders.OrderDetails(
        9007199254740993L, "purchased-uuid", 7L,
        9007199254740993L, "PENDING", "READY", "saved receiver", "saved phone", "saved address", "saved message",
        LocalDateTime.of(2026, 10, 20, 22, 0, 1, 123456789),
        listOf(DirectOrders.Item("saved product", "SAVED-80", 9007199254740993L, 2)),
    )
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(OrderReadController(orders))
            .setControllerAdvice(DirectOrderExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver(), PageableHandlerMethodArgumentResolver())
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

    @ParameterizedTest
    @NullSource
    @EnumSource(OrderStatus::class)
    fun `검증된 회원이 위조된 소유자와 선택적 상태를 보낼 때__목록을 조회하면__검증된 소유자와 기본 10건으로 넘기고 페이지를 그대로 돌려준다`(
        statusFilter: OrderStatus?,
    ) {
        // given
        current(7L)
        val request = StoreMemberReads.Request(0, 10, listOf())
        val call = get("/api/v1/orders").param("userId", "99").header("X-User-Id", "99").header("userId", "99")
        if (statusFilter != null) {
            call.param("status", statusFilter.name)
        }

        every { orders.list(any(), any(), any()) } returns StoreMemberReads.Page(listOf(order), 0, 10, 1, listOf())

        // when
        val bytes = mvc.perform(call).andExpect(status().isOk()).andReturn().response.contentAsByteArray

        // then
        val body = json.readTree(bytes)
        assertFalse(body.has("data"))
        assertEquals(1, body.get("content").size())
        assertEquals(0, body.get("number").intValue())
        assertEquals(10, body.get("size").intValue())
        assertEquals(1L, body.get("totalElements").longValue())
        assertEquals(1, body.get("totalPages").intValue())
        assertTrue(body.get("sort").get("unsorted").booleanValue())
        val item = body.get("content").get(0)
        assertEquals(json.readTree(json.writeValueAsBytes(order)), item)
        assertEquals(12, item.size())
        assertEquals(4, item.get("items").get(0).size())
        assertEquals(9007199254740993L, item.get("orderId").longValue())
        assertEquals(9007199254740993L, item.get("items").get(0).get("price").longValue())
        assertEquals("2026-10-20T22:00:01.123456789", item.get("createdAt").asText())
        verify(exactly = 1) { orders.list(7, statusFilter?.name, request) }
        confirmVerified(orders)
    }

    @Test
    fun `페이지와 상태와 단일 필드 정렬이 명시됐을 때__목록과 기존 상세를 조회하면__페이지 메타데이터와 정렬과 동일한 주문 응답 형태를 유지한다`() {
        // given
        current(7L)
        val sort = listOf(
            StoreMemberReads.Order("totalAmount", true, false),
            StoreMemberReads.Order("receiverName", false, true),
        )
        val request = StoreMemberReads.Request(3, 2, sort)

        every { orders.list(any(), any(), any()) } returns StoreMemberReads.Page(listOf(order), 3, 2, 7, sort)
        every { orders.get(any(), any()) } returns order

        // when
        val history = json.readTree(
            mvc.perform(
                get("/api/v1/orders").param("status", "PAID").param("page", "3").param("size", "2")
                    .param("sort", "totalAmount,asc", "receiverName,desc,ignorecase"),
            )
                .andExpect(status().isOk()).andExpect(jsonPath("\$.number").value(3)).andExpect(jsonPath("\$.size").value(2))
                .andExpect(jsonPath("\$.totalElements").value(7)).andExpect(jsonPath("\$.totalPages").value(4))
                .andExpect(jsonPath("\$.sort.sorted").value(true)).andReturn().response.contentAsByteArray,
        )
        val detail = json.readTree(
            mvc.perform(get("/api/v1/orders/9007199254740993"))
                .andExpect(status().isOk()).andReturn().response.contentAsByteArray,
        )

        // then
        assertEquals(detail, history.get("content").get(0))
        verify(exactly = 1) { orders.list(7, "PAID", request) }
        verify(exactly = 1) { orders.get(7, 9007199254740993L) }
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "non-long"])
    fun `주체가 없거나 Long이 아니고 소유자가 위조됐을 때__목록을 조회하면__애플리케이션 호출 전에 401을 돌려준다`(kind: String) {
        // given
        if (kind == "non-long") {
            current("99")
        }

        // when
        val result = mvc.perform(get("/api/v1/orders").param("userId", "99").header("X-User-Id", "99").header("userId", "99"))

        // then
        result.andExpect(status().isUnauthorized()).andExpect(content().string(""))
        confirmVerified(orders)
    }

    @Test
    fun `지원하지 않는 상태일 때__목록을 조회하면__애플리케이션 호출 없이 원본의 타입 오류를 돌려준다`() {
        // given
        current(7L)

        // when
        val result = mvc.perform(get("/api/v1/orders").param("status", "UNKNOWN"))

        // then
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("status"))
            .andExpect(jsonPath("\$.errors[0].value").value("UNKNOWN"))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))
        confirmVerified(orders)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `현재 행위자나 조회나 데이터베이스가 거부할 때__목록을 조회하면__주문 데이터 없이 정제된 오류를 유지한다`(
        failure: RuntimeException,
        statusCode: Int,
        code: String,
        message: String,
    ) {
        // given
        current(7L)

        every { orders.list(any(), any(), any()) } throws failure

        // when
        val body = mvc.perform(get("/api/v1/orders")).andExpect(status().`is`(statusCode))
            .andExpect(jsonPath("\$.code").value(code))
            .andExpect(jsonPath("\$.message").value(message))
            .andExpect(jsonPath("\$.errors").isEmpty())
            .andReturn().response.contentAsString

        // then
        assertFalse(body.contains("private"))
        assertFalse(body.contains("purchased"))
        assertFalse(body.contains("saved"))
        verify(exactly = 1) { orders.list(7, null, StoreMemberReads.Request(0, 10, listOf())) }
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["/api/orders", "/api/v1/orders/uuid/purchased-uuid", "/api/v1/orders/77/payment", "/api/v1/orders/cart/direct"],
    )
    fun `승인되지 않은 인접 경로일 때__요청하면__주문 내역 핸들러 없이 404를 돌려준다`(path: String) {
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
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(OrderActorRejectedException(), 403, "C006", "접근 권한이 없습니다."),
            Arguments.of(IllegalArgumentException("private query and account"), 400, "C001", "입력값이 올바르지 않습니다."),
            Arguments.of(DataAccessResourceFailureException("private query and account"), 500, "C004", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
        )
    }
}
