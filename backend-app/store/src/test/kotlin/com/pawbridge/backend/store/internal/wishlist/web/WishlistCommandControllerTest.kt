package com.pawbridge.backend.store.internal.wishlist.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.CannotCreateTransactionException
import java.time.LocalDateTime
import java.util.stream.Stream

class WishlistCommandControllerTest {
    private val commands: WishlistCommands = mockk<WishlistCommands>()
    private val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(WishlistCommandController(commands))
            .setControllerAdvice(WishlistCommandExceptionHandler())
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

    @Test
    fun `인증된 회원이고 본문과 헤더의 사용자가 위조됐을 때__찜을 추가하면__인증 주체를 쓰고 원래의 201 응답을 그대로 돌려준다`() {
        // given
        current(7L)

        every { commands.add(any(), any()) } returns WishlistCommands.Added(
            9007199254740993L, 7L, 80L, "SKU", 9L, "상품", null,
            9007199254740993L, mutableMapOf("색상" to "파랑"), LocalDateTime.of(2026, 10, 20, 9, 0, 1, 123456789),
        )

        // when
        val body = mvc.perform(
            post("/api/v1/wishlists").header("X-User-Id", "99").contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":80,\"userId\":99}"),
        ).andExpect(status().isCreated()).andReturn().response.contentAsByteArray

        // then
        val node = json.readTree(body)
        assertEquals(10, node.size())
        assertFalse(node.has("data"))
        assertEquals(7L, node.get("userId").longValue())
        assertEquals(9007199254740993L, node.get("wishlistId").longValue())
        assertEquals(9007199254740993L, node.get("price").longValue())
        assertTrue(node.get("productImageUrl").isNull)
        assertEquals("파랑", node.get("options").get("색상").asText())
        assertEquals("2026-10-20T09:00:01.123456789", node.get("createdAt").asText())
        verify(exactly = 1) { commands.add(7L, 80L) }
    }

    @Test
    fun `인증된 회원이고 쿼리의 사용자가 위조됐을 때__SKU로 찜을 삭제하면__인증 주체를 쓰고 204를 돌려준다`() {
        // given
        current(7L)

        justRun { commands.removeByMemberAndSku(any(), any()) }

        // when
        mvc.perform(delete("/api/v1/wishlists").param("userId", "99").param("skuId", "80").header("X-User-Id", "99"))
            .andExpect(status().isNoContent()).andExpect(content().string(""))

        // then
        verify(exactly = 1) { commands.removeByMemberAndSku(7L, 80L) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "DELETE"])
    fun `인증 주체가 없고 사용자 헤더가 위조됐을 때__찜 명령을 보내면__애플리케이션 호출 없이 401을 돌려준다`(method: String) {
        // given
        val request = if (method == "POST") {
            post("/api/v1/wishlists").contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80,\"userId\":99}")
        } else {
            delete("/api/v1/wishlists").param("skuId", "80").param("userId", "99")
        }

        // when
        mvc.perform(request.header("X-User-Id", "99")).andExpect(status().isUnauthorized())

        // then
        confirmVerified(commands)
    }

    @Test
    fun `인증 주체가 Long이 아닐 때__찜을 추가하면__사용자 문자열을 해석하지 않고 401로 거부한다`() {
        // given
        current("99")

        // when
        mvc.perform(post("/api/v1/wishlists").contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80}"))
            .andExpect(status().isUnauthorized())

        // then
        confirmVerified(commands)
    }

    @ParameterizedTest
    @ValueSource(longs = [33L, 9007199254740993L])
    fun `인증된 회원이고 사용자 출처가 위조됐을 때__ID로 찜을 삭제하면__인증 주체만 쓰고 204를 돌려준다`(
        wishlistId: Long,
    ) {
        // given
        current(7L)

        justRun { commands.removeById(any(), any()) }

        // when
        mvc.perform(
            delete("/api/v1/wishlists/{wishlistId}", wishlistId).param("userId", "99").header("X-User-Id", "99")
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":99}"),
        ).andExpect(status().isNoContent()).andExpect(content().string(""))

        // then
        verify(exactly = 1) { commands.removeById(7L, wishlistId) }
        confirmVerified(commands)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "text"])
    fun `인증 주체가 없거나 Long이 아니고 사용자가 위조됐을 때__ID로 찜을 삭제하면__명령 호출 없이 401을 돌려준다`(
        principal: String,
    ) {
        // given
        if (principal == "text") {
            current("99")
        }

        // when
        mvc.perform(delete("/api/v1/wishlists/33").param("userId", "99").header("X-User-Id", "99"))
            .andExpect(status().isUnauthorized()).andExpect(content().string(""))

        // then
        confirmVerified(commands)
    }

    @ParameterizedTest
    @ValueSource(strings = ["bad", "9223372036854775808", "33.0"])
    fun `해석할 수 없는 ID일 때__ID로 찜을 삭제하면__명령 호출 없이 타입 불일치 400을 돌려준다`(wishlistId: String) {
        // given
        current(7L)

        // when
        mvc.perform(delete("/api/v1/wishlists/{wishlistId}", wishlistId)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("wishlistId"))
            .andExpect(jsonPath("\$.errors[0].value").value(wishlistId))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))

        // then
        confirmVerified(commands)
    }

    @ParameterizedTest
    @MethodSource("identityFailures")
    fun `데이터베이스나 트랜잭션 실패가 발생할 때__ID로 찜을 삭제하면__내부 정보 없이 500을 돌려준다`(
        failure: RuntimeException,
    ) {
        // given
        current(7L)

        every { commands.removeById(any(), any()) } throws failure

        // when
        val body = mvc.perform(delete("/api/v1/wishlists/33")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
            .andExpect(jsonPath("\$.message").value("서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."))
            .andReturn().response.contentAsString

        // then
        assertFalse(body.contains("private delete"))
        verify(exactly = 1) { commands.removeById(7L, 33L) }
        confirmVerified(commands)
    }

    @ParameterizedTest
    @MethodSource("addFailures")
    fun `원본 명령이 실패할 때__찜을 추가하면__상태 코드를 유지하고 내부 정보를 가린다`(
        failure: RuntimeException,
        statusCode: Int,
        code: String,
    ) {
        // given
        current(7L)

        every { commands.add(any(), any()) } throws failure

        // when
        // then
        mvc.perform(post("/api/v1/wishlists").contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80}"))
            .andExpect(status().`is`(statusCode))
            .andExpect(jsonPath("\$.code").value(code))
            .andExpect(jsonPath("\$.errors").isEmpty())
            .andExpect(jsonPath("\$.message").value(if (statusCode == 400) "입력값이 올바르지 않습니다." else "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."))
    }

    @Test
    fun `찜 쌍이 없을 때__SKU로 찜을 삭제하면__원래의 잘못된 입력 오류를 유지한다`() {
        // given
        current(7L)

        every { commands.removeByMemberAndSku(any(), any()) } throws IllegalArgumentException("찜을 찾을 수 없습니다.")

        // when
        // then
        mvc.perform(delete("/api/v1/wishlists").param("skuId", "80")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C001"))
    }

    @Test
    fun `SKU 쿼리 타입이 잘못됐을 때__삭제하면__명령 호출 전에 원래의 필드 오류를 돌려준다`() {
        // given
        current(7L)

        // when
        mvc.perform(delete("/api/v1/wishlists").param("skuId", "bad")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("skuId"))
            .andExpect(jsonPath("\$.errors[0].value").value("bad"))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))

        // then
        confirmVerified(commands)
    }

    companion object {
        @JvmStatic
        fun identityFailures(): Stream<Arguments> = Stream.of(
            Arguments.of(DataAccessResourceFailureException("private delete SQL")),
            Arguments.of(CannotCreateTransactionException("private delete transaction")),
        )

        @JvmStatic
        fun addFailures(): Stream<Arguments> = Stream.of(
            Arguments.of(IllegalStateException("이미 찜한 상품입니다."), 500, "C004"),
            Arguments.of(IllegalArgumentException("SKU를 찾을 수 없습니다: 80"), 400, "C001"),
            Arguments.of(RuntimeException("private SQL detail"), 500, "C004"),
        )
    }
}
