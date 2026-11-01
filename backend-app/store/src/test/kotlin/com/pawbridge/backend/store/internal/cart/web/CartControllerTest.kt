package com.pawbridge.backend.store.internal.cart.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.internal.cart.application.CartActorRejectedException
import com.pawbridge.backend.store.internal.cart.application.Carts
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionSystemException
import java.util.stream.Stream

class CartControllerTest {
    val carts: Carts = mockk<Carts>()
    val json: ObjectMapper = ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(CartController(carts)).setControllerAdvice(CartExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
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
    fun `인증된 회원일 때__장바구니를 조회하면__id가 null이고 Long 값이 정확한 7개 필드 배열을 그대로 돌려준다`() {
        // given
        current(7L)

        every { carts.get(any()) } returns listOf(
            Carts.Item(null, 80L, "LIVE-80", "live product", 9007199254740993L, 2, 18014398509481986L),
        )

        // when
        val body = mvc.perform(request("get").header("X-User-Id", "99").param("userId", "99"))
            .andExpect(status().isOk()).andReturn().response.contentAsByteArray

        // then
        val node = json.readTree(body)
        assertTrue(node.isArray)
        assertEquals(1, node.size())
        val item = node.get(0)
        assertEquals(7, item.size())
        assertTrue(item.get("id").isNull)
        assertEquals(80L, item.get("skuId").longValue())
        assertEquals("LIVE-80", item.get("skuCode").asText())
        assertEquals("live product", item.get("productName").asText())
        assertEquals(9007199254740993L, item.get("price").longValue())
        assertEquals(2, item.get("quantity").intValue())
        assertEquals(18014398509481986L, item.get("totalPrice").longValue())
        verify(exactly = 1) { carts.get(7L) }
        confirmVerified(carts)
    }

    @Test
    fun `장바구니가 비어 있을 때__조회하면__빈 배열을 돌려준다`() {
        // given
        current(7L)

        every { carts.get(any()) } returns listOf()

        // when
        // then
        mvc.perform(request("get")).andExpect(status().isOk()).andExpect(content().json("[]"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["add", "update", "remove", "clear"])
    fun `위조한 사용자 출처가 있는 인증된 회원일 때__장바구니를 변경하면__인증 주체만 쓰고 빈 본문 200을 돌려준다`(
        operation: String,
    ) {
        // given
        current(7L)

        when (operation) {
            "add" -> justRun { carts.add(any(), any(), any()) }
            "update" -> justRun { carts.update(any(), any(), any()) }
            "remove" -> justRun { carts.remove(any(), any()) }
            else -> justRun { carts.clear(any()) }
        }

        // when
        mvc.perform(request(operation).header("X-User-Id", "99").param("userId", "99"))
            .andExpect(status().isOk()).andExpect(content().string(""))

        // then
        when (operation) {
            "add" -> verify(exactly = 1) { carts.add(7L, 80L, 2) }
            "update" -> verify(exactly = 1) { carts.update(7L, 80L, -1) }
            "remove" -> verify(exactly = 1) { carts.remove(7L, 80L) }
            else -> verify(exactly = 1) { carts.clear(7L) }
        }
        confirmVerified(carts)
    }

    @ParameterizedTest
    @MethodSource("invalidPrincipals")
    fun `인증 주체가 없거나 Long이 아닐 때__장바구니 엔드포인트에 접근하면__애플리케이션 호출 전에 401을 돌려준다`(
        kind: String,
        operation: String,
    ) {
        // given
        if (kind == "text") {
            current("99")
        }

        // when
        mvc.perform(request(operation).header("X-User-Id", "99").param("userId", "99"))
            .andExpect(status().isUnauthorized()).andExpect(content().string(""))

        // then
        confirmVerified(carts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["quantity", "skuId"])
    fun `정수가 아닌 인자가 올 때__수량을 패치하면__애플리케이션 호출 없이 원래의 타입 오류를 돌려준다`(field: String) {
        // given
        current(7L)

        val call = if (field == "skuId") {
            patch("/api/v1/carts/items/bad").param("quantity", "1")
        } else {
            patch("/api/v1/carts/items/80").param("quantity", "2147483648")
        }

        // when
        mvc.perform(call).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value(field))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))

        // then
        confirmVerified(carts)
    }

    @Test
    fun `수량이 없을 때__패치하면__애플리케이션 호출 전에 잘못된 입력 오류를 돌려준다`() {
        // given
        current(7L)

        // when
        mvc.perform(patch("/api/v1/carts/items/80")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C001"))

        // then
        confirmVerified(carts)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `행위자 거부나 검증 실패나 영속성 실패가 발생할 때__장바구니에 담으면__내부 정보를 가린 오류를 돌려주고 성공으로 보이지 않는다`(
        failure: RuntimeException,
        statusCode: Int,
        code: String,
        message: String,
    ) {
        // given
        current(7L)

        every { carts.add(any(), any(), any()) } throws failure

        // when
        val body = mvc.perform(request("add")).andExpect(status().`is`(statusCode))
            .andExpect(jsonPath("\$.code").value(code))
            .andExpect(jsonPath("\$.message").value(message))
            .andExpect(jsonPath("\$.errors").isEmpty()).andReturn().response.contentAsString

        // then
        assertFalse(body.contains("private"))
    }

    @Test
    fun `reset 경로일 때__삭제하면__고객용 reset 핸들러가 없어 404를 돌려준다`() {
        // given
        current(7L)

        // when
        mvc.perform(delete("/api/v1/carts/reset")).andExpect(status().isNotFound())

        // then
        confirmVerified(carts)
    }

    companion object {
        fun request(operation: String): MockHttpServletRequestBuilder {
            return when (operation) {
                "get" -> get("/api/v1/carts")
                "add" -> post("/api/v1/carts/items").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"skuId\":80,\"quantity\":2,\"userId\":99}")
                "update" -> patch("/api/v1/carts/items/80").param("quantity", "-1")
                "remove" -> delete("/api/v1/carts/items/80")
                else -> delete("/api/v1/carts")
            }
        }

        @JvmStatic
        fun invalidPrincipals(): Stream<Arguments> {
            return Stream.of("missing", "text").flatMap { kind ->
                Stream.of("get", "add", "update", "remove", "clear").map { op -> Arguments.of(kind, op) }
            }
        }

        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(CartActorRejectedException(), 403, "C006", "접근 권한이 없습니다."),
            Arguments.of(IllegalArgumentException("private overflow"), 400, "C001", "입력값이 올바르지 않습니다."),
            Arguments.of(DataAccessResourceFailureException("private SQL"), 500, "C004", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
            Arguments.of(TransactionSystemException("private commit"), 500, "C004", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
        )
    }
}
