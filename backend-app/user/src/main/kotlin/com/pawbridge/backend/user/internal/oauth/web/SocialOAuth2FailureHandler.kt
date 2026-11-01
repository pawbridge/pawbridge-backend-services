package com.pawbridge.backend.user.internal.oauth.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SocialOAuth2FailureHandler(private val redirectUri: String) : SimpleUrlAuthenticationFailureHandler() {

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        var message = "로그인에 실패했습니다"
        if (exception is OAuth2AuthenticationException &&
            "oauth2_processing_error" == exception.error.errorCode &&
            exception.error.description != null &&
            SAFE.contains(exception.error.description)
        ) {
            message = exception.error.description
        }
        LOG.warn("OAuth2 authentication failed; exceptionType={}", exception.javaClass.name)
        val encoded = URLEncoder.encode(message, StandardCharsets.UTF_8)
        response.sendRedirect(
            response.encodeRedirectURL(
                UriComponentsBuilder.fromUriString(redirectUri)
                    .replacePath("/login")
                    .queryParam("error", encoded)
                    .build()
                    .toUriString(),
            ),
        )
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(SocialOAuth2FailureHandler::class.java)

        private val SAFE: Set<String> = java.util.Set.of(
            "이메일 정보를 가져올 수 없습니다",
            "이미 해당 이메일로 가입된 계정이 있습니다. 관리자에게 문의해주세요.",
            "이미 다른 소셜 계정에 연결된 이메일입니다. 관리자에게 문의해주세요.",
            "로그인 처리 중 오류가 발생했습니다",
        )
    }
}
