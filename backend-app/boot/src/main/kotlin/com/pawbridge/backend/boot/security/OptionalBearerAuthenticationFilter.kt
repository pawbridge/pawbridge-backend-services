package com.pawbridge.backend.boot.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 공개 경로에서 로그인 토큰을 선택적으로 읽는다. 유효한 토큰이면 회원 번호를 인증 주체로 두고,
 * 토큰이 없거나 만료·위조됐으면 401 없이 비로그인으로 진행한다(공개 경로가 토큰 상태 때문에 막히지 않게).
 * 회원 상태(삭제 진행 등)는 조회하지 않는다. [PostBearerAuthenticationFilter]와 같은 서명 검증만 한다.
 */
class OptionalBearerAuthenticationFilter(
    private val tokens: PostTokenAuthenticator,
    private val optionalRequest: RequestMatcher,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return !optionalRequest.matches(request)
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val authorization = request.getHeader("Authorization")
        val authentication = if (authorization == null) {
            null
        } else {
            try {
                tokens.authenticateToken(authorization)
            } catch (ignored: PostTokenAuthenticator.Failure) {
                null
            }
        }
        if (authentication == null) {
            filterChain.doFilter(request, response)
            return
        }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        try {
            filterChain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
