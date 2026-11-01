package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.LocalLoginConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.ninjasquad.springmockk.MockkBean
import io.mockk.confirmVerified
import io.mockk.every
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional

/**
 * The local login lives in its own SecurityFilterChain (order 0), separate from the main chain.
 * A browser on https://www.pawbridge.kr calls https://api.pawbridge.kr, so this chain must answer
 * the CORS preflight itself, before LocalLoginFilter tries to authenticate an OPTIONS request.
 */
@WebMvcTest(
    properties = ["pawbridge.auth.local-enabled=true", "pawbridge.auth.sessions-enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [LocalLoginConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class LocalLoginCorsHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    // Replaces the JDBC based LocalLogin bean, so no database is needed.
    @MockkBean
    lateinit var login: LocalLogin

    @ParameterizedTest
    @ValueSource(strings = ["https://www.pawbridge.kr", "https://pawbridge.kr", "http://localhost:5173", "http://127.0.0.1:3000"])
    fun `허용된 출처일 때__로그인을 사전 요청하면__CORS 헤더와 함께 200을 돌려주고 인증하지 않는다`(origin: String) {
        // given
        val preflight = options(LOGIN_PATH)
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type")

        // when
        val result = mvc.perform(preflight)

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", origin))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
        confirmVerified(login)
    }

    @Test
    fun `허용되지 않은 출처일 때__로그인을 사전 요청하면__CORS 헤더 없이 403을 돌려준다`() {
        // given
        val preflight = options(LOGIN_PATH)
            .header("Origin", DISALLOWED_ORIGIN)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type")

        // when
        val result = mvc.perform(preflight)

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
        confirmVerified(login)
    }

    @Test
    fun `허용된 출처일 때__로그인하면__로그인 응답이 CORS 헤더를 싣는다`() {
        // given
        every { login.findByEmail(any()) } returns Optional.of(registeredMember())
        every { login.complete(any()) } returns completedLogin()

        // when
        val result = mvc.perform(
            post(LOGIN_PATH)
                .header("Origin", ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_LOGIN_BODY),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
            .andExpect(jsonPath("$.data.accessToken").value("synthetic-access"))
    }

    @Test
    fun `허용된 출처에서 자격 증명이 틀렸을 때__로그인하면__401도 CORS 헤더를 싣는다`() {
        // given
        every { login.findByEmail(any()) } returns Optional.of(registeredMember())

        // when
        val result = mvc.perform(
            post(LOGIN_PATH)
                .header("Origin", ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"member@example.invalid\",\"password\":\"wrong\"}"),
        )

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
    }

    @Test
    fun `허용되지 않은 출처일 때__사전 요청 없이 로그인하면__CORS 헤더 없이 정상 응답을 유지한다`() {
        // given
        every { login.findByEmail(any()) } returns Optional.of(registeredMember())
        every { login.complete(any()) } returns completedLogin()

        // when
        val result = mvc.perform(
            post(LOGIN_PATH)
                .header("Origin", DISALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_LOGIN_BODY),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }

    private fun registeredMember(): LocalLogin.Account {
        return LocalLogin.Account(
            7L,
            "member@example.invalid",
            "Member",
            BCryptPasswordEncoder().encode(PASSWORD),
            "ROLE_USER",
            null,
        )
    }

    private fun completedLogin(): LocalLogin.CompletedLogin {
        return LocalLogin.CompletedLogin(
            "synthetic-access",
            IssuedRefreshToken("DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD", 60),
        )
    }

    companion object {
        private const val LOGIN_PATH = "/api/v1/auth/login"
        private const val ALLOWED_ORIGIN = "https://www.pawbridge.kr"
        private const val DISALLOWED_ORIGIN = "https://evil.example"
        private const val PASSWORD = "correct-password"
        private const val VALID_LOGIN_BODY = "{\"email\":\"member@example.invalid\",\"password\":\"" + PASSWORD + "\"}"
    }
}
