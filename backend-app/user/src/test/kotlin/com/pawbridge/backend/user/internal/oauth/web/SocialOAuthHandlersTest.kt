package com.pawbridge.backend.user.internal.oauth.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.oauth.infrastructure.SocialOAuthPrincipal
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.web.DefaultRedirectStrategy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SocialOAuthHandlersTest {
    @Test
    fun `저장된 소셜 회원일 때__성공을 마무리하면__토큰 없이 리다이렉트하고 리프레시 쿠키를 설정한다`() {
        // given
        val login = mockk<SocialLogin>()
        val response = mockk<HttpServletResponse>()
        val user = SocialLogin.SocialUser(7, "fixture@example.invalid", "name", "ROLE_USER", null, "GOOGLE")

        every { login.complete(any()) } returns IssuedRefreshToken(SYNTHETIC_REFRESH, 1_209_600)
        every { response.encodeRedirectURL(any()) } answers { firstArg() }
        justRun { response.addHeader(any(), any()) }
        justRun { response.sendRedirect(any()) }

        val redirect = LoggerFactory.getLogger(DefaultRedirectStrategy::class.java) as Logger
        val previous = redirect.level
        val events = ListAppender<ILoggingEvent>()
        events.start()
        redirect.addAppender(events)
        redirect.level = Level.DEBUG
        try {
            // when
            SocialOAuth2SuccessHandler(login, FRONT).onAuthenticationSuccess(
                mockk<HttpServletRequest>(),
                response,
                UsernamePasswordAuthenticationToken(SocialOAuthPrincipal(user, mapOf()), null),
            )

            // then
            val url = slot<String>()
            verify(exactly = 1) { response.sendRedirect(capture(url)) }
            assertEquals(FRONT, url.captured)
            assertNull(URI.create(url.captured).rawQuery)
            val cookie = slot<String>()
            verify(exactly = 1) { response.addHeader("Set-Cookie", capture(cookie)) }
            assertEquals(
                "__Secure-pb_refresh=" + SYNTHETIC_REFRESH + "; Path=/api/v1/auth; Max-Age=1209600; " +
                    "Expires=" + cookieExpiry(cookie.captured) + "; Secure; HttpOnly; SameSite=Lax",
                cookie.captured,
            )
            assertFalse(cookie.captured.contains("Domain="))
            verify(exactly = 1) { login.complete(user) }
            for (event in events.list) {
                assertFalse(event.formattedMessage.contains(SYNTHETIC_REFRESH))
            }
        } finally {
            redirect.level = previous
            redirect.detachAppender(events)
            events.stop()
        }
    }

    private fun cookieExpiry(header: String): String {
        val start = header.indexOf("Expires=") + "Expires=".length
        return header.substring(start, header.indexOf(';', start))
    }

    @ParameterizedTest
    @ValueSource(strings = ["provider", "forged-processing", "db-completion"])
    fun `비공개 토큰이 든 예외일 때__실패를 리다이렉트하면__고정된 URL과 타입만 담은 로그만 남긴다`(
        source: String,
    ) {
        // given
        val response = mockk<HttpServletResponse>()
        val request = mockk<HttpServletRequest>()

        every { response.encodeRedirectURL(any()) } answers { firstArg() }
        justRun { response.sendRedirect(any()) }

        val logger = LoggerFactory.getLogger(
            if (source == "db-completion") SocialOAuth2SuccessHandler::class.java else SocialOAuth2FailureHandler::class.java,
        ) as Logger
        val events = ListAppender<ILoggingEvent>()
        events.start()
        logger.addAppender(events)
        try {
            // when
            if (source == "db-completion") {
                val login = mockk<SocialLogin>()
                val user = SocialLogin.SocialUser(7, "fixture@example.invalid", "name", "ROLE_USER", null, "GOOGLE")
                every { login.complete(any()) } throws IllegalStateException(PRIVATE)
                SocialOAuth2SuccessHandler(login, FRONT).onAuthenticationSuccess(
                    request,
                    response,
                    UsernamePasswordAuthenticationToken(SocialOAuthPrincipal(user, mapOf()), null),
                )
            } else {
                val error = OAuth2AuthenticationException(
                    OAuth2Error(if (source == "provider") "server_error" else "oauth2_processing_error", PRIVATE, null),
                )
                SocialOAuth2FailureHandler(FRONT).onAuthenticationFailure(request, response, error)
            }

            // then
            val url = slot<String>()
            verify(exactly = 1) { response.sendRedirect(capture(url)) }
            assertFalse(url.captured.contains(PRIVATE))
            assertTrue(url.captured.startsWith("https://fixture.invalid/login?error="))
            assertFalse(events.list.isEmpty())
            for (event in events.list) {
                assertFalse(event.formattedMessage.contains(PRIVATE))
                assertNull(event.throwableProxy)
            }
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["?tenant=a", "#oauth-error"])
    fun `프런트엔드 URL에 쿼리나 프래그먼트가 있을 때__토큰 완료가 실패하면__이를 보존하고 고정 오류 파라미터를 덧붙인다`(
        suffix: String,
    ) {
        // given
        val login = mockk<SocialLogin>()
        val response = mockk<HttpServletResponse>()
        val user = SocialLogin.SocialUser(7, "fixture@example.invalid", "name", "ROLE_USER", null, "GOOGLE")

        every { login.complete(any()) } throws IllegalStateException(PRIVATE)
        every { response.encodeRedirectURL(any()) } answers { firstArg() }
        justRun { response.sendRedirect(any()) }

        // when
        SocialOAuth2SuccessHandler(login, FRONT + suffix).onAuthenticationSuccess(
            mockk<HttpServletRequest>(),
            response,
            UsernamePasswordAuthenticationToken(SocialOAuthPrincipal(user, mapOf()), null),
        )

        // then
        val url = slot<String>()
        verify(exactly = 1) { response.sendRedirect(capture(url)) }
        val target = URI.create(url.captured)
        val error = URLEncoder.encode("로그인 처리 중 오류가 발생했습니다", StandardCharsets.UTF_8)
        assertEquals("/login", target.path)
        assertEquals(if (suffix.startsWith("?")) "tenant=a&error=" + error else "error=" + error, target.rawQuery)
        assertEquals(if (suffix.startsWith("#")) "oauth-error" else null, target.fragment)
        assertFalse(url.captured.contains(PRIVATE))
        verify(exactly = 1) { login.complete(user) }
    }

    @Test
    fun `알려진 정적 로컬 계정 충돌일 때__OAuth가 실패하면__그 허용된 사유만 유지한다`() {
        // given
        val response = mockk<HttpServletResponse>()
        val message = "이미 해당 이메일로 가입된 계정이 있습니다. 관리자에게 문의해주세요."

        every { response.encodeRedirectURL(any()) } answers { firstArg() }
        justRun { response.sendRedirect(any()) }

        // when
        SocialOAuth2FailureHandler(FRONT).onAuthenticationFailure(
            mockk<HttpServletRequest>(),
            response,
            OAuth2AuthenticationException(OAuth2Error("oauth2_processing_error", message, null)),
        )

        // then
        val url = slot<String>()
        verify(exactly = 1) { response.sendRedirect(capture(url)) }
        assertTrue(url.captured.contains(URLEncoder.encode(message, StandardCharsets.UTF_8)))
    }

    companion object {
        private const val FRONT = "https://fixture.invalid/oauth/callback"
        private const val PRIVATE = "synthetic-private-access-refresh-secret"
        private const val SYNTHETIC_REFRESH = "c3ludGhldGljLXJlZnJlc2gtdG9rZW4tdmFsdWUtMDEy"
    }
}
