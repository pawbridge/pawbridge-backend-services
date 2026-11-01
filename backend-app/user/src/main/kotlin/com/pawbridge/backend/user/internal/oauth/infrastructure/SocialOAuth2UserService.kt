package com.pawbridge.backend.user.internal.oauth.infrastructure

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.OAuth2User

class SocialOAuth2UserService(
    private val login: SocialLogin,
    private val remote: OAuth2UserService<OAuth2UserRequest, OAuth2User>,
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    constructor(login: SocialLogin) : this(login, DefaultOAuth2UserService())

    override fun loadUser(userRequest: OAuth2UserRequest): OAuth2User {
        try {
            val raw = remote.loadUser(userRequest)
            return SocialOAuthPrincipal(
                login.load(userRequest.clientRegistration.registrationId, raw.attributes),
                raw.attributes,
            )
        } catch (rejected: SocialLoginRejectedException) {
            val reason = if (SAFE_REASONS.contains(rejected.message)) rejected.message else GENERIC_REASON
            throw OAuth2AuthenticationException(OAuth2Error("oauth2_processing_error", reason, null))
        } catch (failure: RuntimeException) {
            LOG.error("OAuth2 user processing failed; exceptionType={}", failure.javaClass.name)
            throw OAuth2AuthenticationException(OAuth2Error("oauth2_processing_error", GENERIC_REASON, null))
        }
    }

    private companion object {
        private const val GENERIC_REASON = "로그인 처리 중 오류가 발생했습니다"

        private val SAFE_REASONS: Set<String> = java.util.Set.of(
            "이메일 정보를 가져올 수 없습니다",
            "이미 해당 이메일로 가입된 계정이 있습니다. 관리자에게 문의해주세요.",
            "이미 다른 소셜 계정에 연결된 이메일입니다. 관리자에게 문의해주세요.",
            "로그인 처리 중 오류가 발생했습니다",
        )

        private val LOG = LoggerFactory.getLogger(SocialOAuth2UserService::class.java)
    }
}
