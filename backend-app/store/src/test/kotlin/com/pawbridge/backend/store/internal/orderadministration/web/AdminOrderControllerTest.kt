package com.pawbridge.backend.store.internal.orderadministration.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrderNotFoundException
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import com.pawbridge.backend.store.internal.orderadministration.application.AdministratorRejectedException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.PersistenceException
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
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionSystemException
import java.time.LocalDateTime
import java.util.stream.Stream

class AdminOrderControllerTest {
    val orders: AdminOrders = mockk<AdminOrders>()
    val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    val detail = DirectOrders.OrderDetails(
        9007199254740993L, "stored-uuid", 88L, 9007199254740993L, "PENDING", "READY", "receiver", "phone", "address", "message",
        LocalDateTime.of(2026, 10, 21, 2, 30, 1, 123456789),
        listOf(DirectOrders.Item("stored product", "SKU-80", 9007199254740993L, 1)),
    )
    lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(AdminOrderController(orders))
            .setControllerAdvice(AdminOrderExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver(), PageableHandlerMethodArgumentResolver())
            .setMessageConverters(MappingJackson2HttpMessageConverter(json)).build()
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    fun current(user: Any) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "unused", listOf<GrantedAuthority>())
    }

    @Test
    fun `관리자 주문 목록 필터와 정렬 페이지가 지정됐을 때__검증된 호출자로 목록을 조회하면__원래의 페이지 응답과 외부 사용자 필터를 그대로 돌려준다`() {
        // given
        current(7L)
        val query = AdminOrders.Query("PAID", "SHIPPING", 88L, " A%_ B ", "totalAmount", "ASC", 2, 3)

        every { orders.list(any(), any()) } returns AdminOrders.Page(listOf(detail), 2, 3, 11, "totalAmount", true)

        // when
        val bytes = mvc.perform(
            get("/api/v1/admin/orders").header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
                .param("status", "PAID").param("deliveryStatus", "SHIPPING").param("userId", "88")
                .param("keyword", " A%_ B ")
                .param("sortBy", "totalAmount").param("sortOrder", "ASC").param("page", "2").param("size", "3"),
        ).andExpect(status().isOk()).andReturn().response.contentAsByteArray

        // then
        val page = json.readTree(bytes)
        assertFalse(page.has("data"))
        assertEquals(2, page.get("number").intValue())
        assertEquals(3, page.get("size").intValue())
        assertEquals(11L, page.get("totalElements").longValue())
        assertEquals(4, page.get("totalPages").intValue())
        assertFalse(page.get("first").booleanValue())
        assertFalse(page.get("last").booleanValue())
        assertEquals(1, page.get("numberOfElements").intValue())
        assertEquals(12, page.get("content").get(0).size())
        assertEquals(json.readTree(json.writeValueAsBytes(detail)), page.get("content").get(0))
        assertTrue(page.get("sort").get("sorted").booleanValue())
        verify(exactly = 1) { orders.list(7, query) }
        confirmVerified(orders)
    }

    @Test
    fun `조건 없이 요청했을 때__목록을 조회하면__생성일 내림차순 기본 20건의 빈 페이지를 돌려준다`() {
        // given
        current(7L)
        val query = AdminOrders.Query(null, null, null, null, "createdAt", "desc", 0, 20)

        every { orders.list(any(), any()) } returns AdminOrders.Page(listOf(), 0, 20, 0, "createdAt", false)

        // when
        val result = mvc.perform(request("list"))

        // then
        result.andExpect(status().isOk())
            .andExpect(jsonPath("\$.content").isEmpty())
            .andExpect(jsonPath("\$.number").value(0))
            .andExpect(jsonPath("\$.size").value(20))
        verify(exactly = 1) { orders.list(7, query) }
    }

    @Test
    fun `검증된 호출자가 있을 때__주문 상세를 조회하면__원래의 주문 상세를 그대로 돌려준다`() {
        // given
        current(7L)

        every { orders.get(any(), any()) } returns detail

        // when
        val bytes = mvc.perform(request("get").header("X-User-Id", "99")).andExpect(status().isOk())
            .andReturn().response.contentAsByteArray

        // then
        assertEquals(json.readTree(json.writeValueAsBytes(detail)), json.readTree(bytes))
        verify(exactly = 1) { orders.get(7, 33) }
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["READY", "SHIPPING", "DELIVERED"])
    fun `배송 상태 열거값과 위조된 다른 필드가 있을 때__배송 상태를 변경하면__배송 상태와 검증된 호출자만 넘긴다`(
        delivery: String,
    ) {
        // given
        current(7L)

        every { orders.updateDelivery(any(), any(), any()) } returns detail

        // when
        val result = mvc.perform(
            patch("/api/v1/admin/orders/33/delivery-status").header("X-User-Id", "99")
                .header("X-User-Role", "ROLE_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryStatus\":\"" + delivery + "\",\"status\":\"PAID\",\"userId\":99}"),
        )

        // then
        result.andExpect(status().isOk()).andExpect(jsonPath("\$.status").value("PENDING"))
        verify(exactly = 1) { orders.updateDelivery(7, 33, delivery) }
        confirmVerified(orders)
    }

    @ParameterizedTest
    @MethodSource("principals")
    fun `주체가 없거나 숫자가 아니고 관리자 헤더가 위조됐을 때__어느 엔드포인트든 호출하면__애플리케이션 호출 없이 401을 돌려준다`(
        kind: String,
        op: String,
    ) {
        // given
        if (kind == "text") {
            current("99")
        }

        // when
        val result = mvc.perform(request(op).header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN"))

        // then
        result.andExpect(status().isUnauthorized()).andExpect(content().string(""))
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["list", "get", "write", "status"])
    fun `현재 DB 기준 관리자가 거부됐을 때__어느 엔드포인트든 호출하면__403과 C006 코드를 돌려준다`(op: String) {
        // given
        current(7L)
        val failure = AdministratorRejectedException()

        if (op == "list") {
            every { orders.list(any(), any()) } throws failure
        } else if (op == "get") {
            every { orders.get(any(), any()) } throws failure
        } else if (op == "status") {
            every { orders.updateStatus(any(), any(), any()) } throws failure
        } else {
            every { orders.updateDelivery(any(), any(), any()) } throws failure
        }

        // when
        val result = mvc.perform(request(op))

        // then
        result.andExpect(status().isForbidden()).andExpect(jsonPath("\$.code").value("C006"))
    }

    @Test
    fun `없는 주문일 때__조회와 변경을 하면__원래의 404와 400 구분을 지킨다`() {
        // given
        current(7L)

        every { orders.get(any(), any()) } throws AdminOrderNotFoundException()
        every { orders.updateDelivery(any(), any(), any()) } throws IllegalArgumentException("private missing id")
        every { orders.updateStatus(any(), any(), any()) } throws IllegalArgumentException("private missing id")

        // when
        val readResult = mvc.perform(request("get"))
        val writeResult = mvc.perform(request("write"))
        val statusResult = mvc.perform(request("status"))

        // then
        readResult.andExpect(status().isNotFound())
            .andExpect(jsonPath("\$.code").value("O001"))
            .andExpect(jsonPath("\$.message").value("주문을 찾을 수 없습니다."))
        writeResult.andExpect(status().isBadRequest()).andExpect(jsonPath("\$.code").value("C001"))
        statusResult.andExpect(status().isBadRequest()).andExpect(jsonPath("\$.code").value("C001"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "deliveryStatus", "userId"])
    fun `목록 쿼리 타입이 잘못됐을 때__목록을 조회하면__애플리케이션 호출 전에 필드 타입 오류를 돌려준다`(field: String) {
        // given
        current(7L)

        // when
        val result = mvc.perform(get("/api/v1/admin/orders").param(field, "bad"))

        // then
        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value(field))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["get", "write", "status"])
    fun `주문 ID 타입이 잘못됐을 때__접근하면__원래의 잘못된 타입 계약을 따른다`(op: String) {
        // given
        current(7L)
        val call = if (op == "get") {
            get("/api/v1/admin/orders/bad")
        } else if (op == "status") {
            patch("/api/v1/admin/orders/bad/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PAID\"}")
        } else {
            patch("/api/v1/admin/orders/bad/delivery-status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryStatus\":\"READY\"}")
        }

        // when
        val result = mvc.perform(call)

        // then
        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("orderId"))
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"deliveryStatus\":null}"])
    fun `배송 상태 값이 null일 때__배송 상태를 변경하면__원래의 500 일반 실패 계열을 유지한다`(body: String) {
        // given
        current(7L)

        every { orders.updateDelivery(any(), any(), any()) } throws IllegalStateException("Delivery status cannot be stored")

        // when
        val response = mvc.perform(
            patch("/api/v1/admin/orders/33/delivery-status").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004")).andReturn().response.contentAsString

        // then
        assertFalse(response.contains("private"))
        verify(exactly = 1) { orders.updateDelivery(7, 33, null) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{\"deliveryStatus\":\"UNKNOWN\"}", "{"])
    fun `잘못된 열거값 JSON이거나 깨진 JSON일 때__배송 상태를 변경하면__원래의 일반 서버 오류 바인딩 계열을 돌려준다`(
        body: String,
    ) {
        // given
        current(7L)

        // when
        val result = mvc.perform(
            patch("/api/v1/admin/orders/33/delivery-status").contentType(MediaType.APPLICATION_JSON).content(body),
        )

        // then
        result.andExpect(status().isInternalServerError()).andExpect(jsonPath("\$.code").value("C004"))
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "commit"])
    fun `DB나 커밋 실패가 났을 때__배송 상태를 변경하면__SQL이나 주문 정보 없이 500을 돌려준다`(kind: String) {
        // given
        current(7L)
        val failure: RuntimeException = if (kind == "query") {
            PersistenceException("private query")
        } else {
            TransactionSystemException("private commit")
        }

        every { orders.updateDelivery(any(), any(), any()) } throws failure

        // when
        val body = mvc.perform(request("write")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
            .andReturn().response.contentAsString

        // then
        assertFalse(body.contains("private"))
        assertFalse(body.contains("address"))
    }

    @Test
    fun `이식되지 않은 결제 경로일 때__요청하면__핸들러가 없어 404를 돌려준다`() {
        // given
        current(7L)

        // when
        val result = mvc.perform(post("/api/v1/orders/33/payment"))

        // then
        result.andExpect(status().isNotFound())
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PENDING", "PAID", "COMPLETED", "CANCELLED", "FAILED"])
    fun `주문 상태 열거값과 위조된 다른 필드가 있을 때__주문 상태를 변경하면__상태와 검증된 호출자만 넘긴다`(
        status: String,
    ) {
        // given
        current(7L)

        every { orders.updateStatus(any(), any(), any()) } returns detail

        // when
        val bytes = mvc.perform(
            patch("/api/v1/admin/orders/33/status").header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\",\"deliveryStatus\":\"DELIVERED\",\"userId\":99}"),
        ).andExpect(status().isOk()).andReturn().response.contentAsByteArray

        // then
        assertEquals(json.readTree(json.writeValueAsBytes(detail)), json.readTree(bytes))
        verify(exactly = 1) { orders.updateStatus(7, 33, status) }
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"status\":null}"])
    fun `주문 상태 값이 null일 때__주문 상태를 변경하면__원래의 500 일반 실패 계열을 유지한다`(body: String) {
        // given
        current(7L)

        every { orders.updateStatus(any(), any(), any()) } throws IllegalStateException("Order status cannot be stored")

        // when
        val result = mvc.perform(
            patch("/api/v1/admin/orders/33/status").contentType(MediaType.APPLICATION_JSON).content(body),
        )

        // then
        result.andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
        verify(exactly = 1) { orders.updateStatus(7, 33, null) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{\"status\":\"UNKNOWN\"}", "{"])
    fun `잘못된 상태 열거값이거나 깨진 JSON일 때__주문 상태를 변경하면__원래의 일반 서버 오류 바인딩 계열을 돌려준다`(
        body: String,
    ) {
        // given
        current(7L)

        // when
        val result = mvc.perform(
            patch("/api/v1/admin/orders/33/status").contentType(MediaType.APPLICATION_JSON).content(body),
        )

        // then
        result.andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
        confirmVerified(orders)
    }

    companion object {
        fun request(op: String): MockHttpServletRequestBuilder {
            return when (op) {
                "list" -> get("/api/v1/admin/orders")
                "get" -> get("/api/v1/admin/orders/33")
                "status" -> patch("/api/v1/admin/orders/33/status").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"PAID\",\"deliveryStatus\":\"SHIPPING\",\"role\":\"ROLE_ADMIN\",\"userId\":99}")
                else -> patch("/api/v1/admin/orders/33/delivery-status").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"deliveryStatus\":\"SHIPPING\",\"role\":\"ROLE_ADMIN\",\"userId\":99,\"status\":\"PAID\"}")
            }
        }

        @JvmStatic
        fun principals(): Stream<Arguments> {
            return Stream.of("missing", "text")
                .flatMap { kind -> Stream.of("list", "get", "write", "status").map { op -> Arguments.of(kind, op) } }
        }
    }
}
