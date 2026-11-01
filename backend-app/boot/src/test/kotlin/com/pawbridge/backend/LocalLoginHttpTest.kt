package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.LocalLoginConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import com.ninjasquad.springmockk.MockkBean
import io.jsonwebtoken.Jwts
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Optional

@WebMvcTest(
    properties = [
        "pawbridge.auth.local-enabled=true",
        "pawbridge.auth.sessions-enabled=true",
        "logging.level.org.springframework.security=DEBUG",
    ],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(
    classes = [
        LocalLoginConfiguration::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        LocalLoginHttpTest.Inputs::class,
    ],
)
@ExtendWith(OutputCaptureExtension::class)
class LocalLoginHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var login: LocalLogin

    @ParameterizedTest
    @ValueSource(strings = ["POST", "GET"])
    fun `유효한 자격 증명과 위조 신원 헤더일 때__정확한 경로로 로그인하면__접근 토큰은 본문에 갱신 토큰은 쿠키에만 담긴다`(
        method: String,
    ) {
        // given
        val role = if (method == "POST") "ROLE_USER" else "ROLE_SHELTER"
        val care: String? = if (method == "POST") null else "synthetic-care"
        val account = account(HASH, role, care)

        every { login.findByEmail(any()) } returns Optional.of(account)
        every { login.complete(any()) } answers {
            LocalLogin.CompletedLogin(
                TOKENS.access(JwtSessionTokens.UserClaims(7, account.email, account.name, role, care)),
                IssuedRefreshToken(REFRESH, 1_209_600),
            )
        }

        // when
        val result = mvc.perform(
            request(HttpMethod.valueOf(method), "/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"MiXeD@example.invalid\",\"password\":\"" + PASSWORD + "\"}")
                .header("Authorization", "Bearer invalid")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN"),
        )
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Authorization"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("로그인에 성공했습니다."))
            .andExpect(jsonPath("$.data.userId").value(7))
            .andExpect(jsonPath("$.data.email").value(account.email))
            .andExpect(jsonPath("$.data.name").value(account.name))
            .andExpect(jsonPath("$.data.role").value(role))
            .andExpect(jsonPath("$.data.password").doesNotExist())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn()

        // then
        val data = ObjectMapper().readTree(result.response.contentAsString).path("data")
        assertThat(data.size()).isEqualTo(6)
        if (care == null) {
            assertThat(data.path("careRegNo").isNull()).isTrue()
        } else {
            assertThat(data.path("careRegNo").asText()).isEqualTo(care)
        }
        val parser = Jwts.parser().verifyWith(F3TestInputs.KEY).build()
        val access = parser.parseSignedClaims(data.path("accessToken").asText()).payload
        assertThat(access.get("userId", Number::class.java).toLong()).isEqualTo(7)
        assertThat(access.get("role", String::class.java)).isEqualTo(role)
        assertThat(result.response.contentAsString).doesNotContain(REFRESH)

        val cookie = result.response.getHeader("Set-Cookie")
        assertThat(cookie).startsWith("__Secure-pb_refresh=" + REFRESH + "; Path=/api/v1/auth; Max-Age=1209600; Expires=")
            .endsWith("; Secure; HttpOnly; SameSite=Lax")
            .doesNotContain("Domain=")
        assertThat(result.response.getHeaders("Set-Cookie")).hasSize(1)
        assertThat(result.request.getSession(false)).isNull()
        verify(exactly = 1) { login.findByEmail("MiXeD@example.invalid") }
        verify(exactly = 1) { login.complete(account) }
        confirmVerified(login)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "wrong", "oauth-placeholder", "null-stored", "missing-password"])
    fun `없거나 틀린 자격 증명일 때__로그인하면__같은 숨김 401을 돌려주고 토큰을 저장하지 않는다`(kind: String) {
        // given
        val stored = when (kind) {
            "oauth-placeholder" -> "OAuth2"
            "null-stored" -> null
            else -> HASH
        }
        val account = account(stored, "ROLE_USER", null)
        val input = when (kind) {
            "wrong" -> "wrong-synthetic-password"
            "missing-password" -> null
            else -> PASSWORD
        }
        val body = ObjectMapper().createObjectNode().put("email", "synthetic@example.invalid")
        if (input != null) {
            body.put("password", input)
        }

        every { login.findByEmail(any()) } returns if (kind == "missing") Optional.empty() else Optional.of(account)

        // when
        val result = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body.toString()))

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json(
                    "{\"code\":401,\"message\":\"이메일 또는 비밀번호가 일치하지 않습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 0) { login.complete(any()) }
    }

    @Test
    fun `이메일을 MVC에서 검증하지 않을 때__원래 필터로 로그인하면__이메일 정책을 더하지 않는다`() {
        // given
        val account = account(HASH, "ROLE_USER", null)

        every { login.findByEmail(any()) } returns Optional.of(account)
        every { login.complete(any()) } returns
            LocalLogin.CompletedLogin("synthetic-access", IssuedRefreshToken(REFRESH, 60))

        // when
        val result = mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"not-email\",\"password\":\"" + PASSWORD + "\"}"),
        )

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) { login.findByEmail("not-email") }
        verify(exactly = 1) { login.complete(account) }
    }

    @Test
    fun `계정 조회가 실패했을 때__DAO 인증하면__로그에 내부 원인 없이 원래의 일반 401을 돌려준다`(
        output: CapturedOutput,
    ) {
        // given
        every { login.findByEmail(any()) } throws DataAccessResourceFailureException(PRIVATE)

        // when
        val result = mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"synthetic@example.invalid\",\"password\":\"" + PASSWORD + "\"}"),
        )

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json("{\"code\":401,\"message\":\"인증에 실패했습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 0) { login.complete(any()) }
        assertThat(output.all).doesNotContain(PRIVATE, PASSWORD, HASH)
    }

    @Test
    fun `유효한 비밀번호 뒤 갱신 토큰 저장이 실패했을 때__로그인하면__토큰이나 내부 원인 없이 원래 실패를 전파한다`(
        output: CapturedOutput,
    ) {
        // given
        val account = account(HASH, "ROLE_USER", null)

        every { login.findByEmail(any()) } returns Optional.of(account)
        every { login.complete(any()) } throws DataAccessResourceFailureException(PRIVATE)

        // when
        val failure = catchThrowable {
            mvc.perform(
                post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content("{\"email\":\"synthetic@example.invalid\",\"password\":\"" + PASSWORD + "\"}"),
            )
        }

        // then
        assertThat(failure).isNotNull()
        assertThat(trace(failure)).doesNotContain(PRIVATE, PASSWORD, HASH)
        assertThat(output.all).doesNotContain(PRIVATE, PASSWORD, HASH)
        verify(exactly = 1) { login.complete(account) }
    }

    @Test
    fun `내부 입력이 든 잘못된 JSON일 때__로그인하면__본문 원인 없이 원래 필터 예외가 난다`(
        output: CapturedOutput,
    ) {
        // given
        val request = post("/api/v1/auth/login").contentType("application/json").content("{\"password\":\"" + PRIVATE + "\"")

        // when
        val failure = catchThrowable { mvc.perform(request) }

        // then
        assertThat(failure).isNotNull()
        assertThat(trace(failure)).doesNotContain(PRIVATE)
        assertThat(output.all).doesNotContain(PRIVATE)
        confirmVerified(login)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/auth/login/extra", "/api/v1/auth/login/", "/api/auth/login"])
    fun `인접 경로일 때__요청하면__로그인이나 정책 확장 없이 거절한다`(path: String) {
        // given
        val request = post(path).contentType("application/json").content("{}")

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(login)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Inputs {
        @Bean
        fun sessionTokens(): JwtSessionTokens = TOKENS

        @Bean
        fun authSessionStore(): JdbcAuthSessionStore {
            return mockk<JdbcAuthSessionStore>()
        }

        // Spring runs the InitializingBean callback when it registers these as beans, so a strict mock must accept it.
        @Bean("writeJdbc")
        fun jdbc(): JdbcTemplate {
            val jdbc = mockk<JdbcTemplate>()
            justRun { jdbc.afterPropertiesSet() }
            return jdbc
        }

        @Bean("writeTransactionManager")
        fun manager(): JdbcTransactionManager {
            val manager = mockk<JdbcTransactionManager>()
            justRun { manager.afterPropertiesSet() }
            return manager
        }
    }

    companion object {
        private const val PASSWORD = "synthetic-local-password"
        private val HASH: String = BCryptPasswordEncoder(4).encode(PASSWORD)
        private const val PRIVATE = "synthetic-private-login-failure-marker"
        private val TOKENS = JwtSessionTokens(F3TestInputs.KEY, 600000)
        private const val REFRESH = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"

        private fun account(password: String?, role: String, care: String?): LocalLogin.Account {
            return LocalLogin.Account(7, "synthetic@example.invalid", "synthetic-name", password, role, care)
        }

        private fun trace(failure: Throwable): String {
            val text = StringWriter()
            failure.printStackTrace(PrintWriter(text))
            return text.toString()
        }
    }
}

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(
    classes = [LocalLoginConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class LocalLoginDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var login: LocalLogin

    @Test
    fun `로컬 로그인이 기본으로 꺼졌을 때__자격 증명과 신원이 주어지면__인증 없이 기존 403을 돌려준다`() {
        // given
        val request = post("/api/v1/auth/login")
            .contentType("application/json")
            .content("{\"email\":\"synthetic@example.invalid\",\"password\":\"synthetic-password\"}")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(login)
    }
}
