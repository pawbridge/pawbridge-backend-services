package com.pawbridge.backend.user.internal.login.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.session.web.RefreshCookies
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.InternalAuthenticationServiceException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import java.io.IOException

/** The original JSON authentication filter; no MVC validation or additional method policy. */
class LocalLoginFilter(
    manager: AuthenticationManager,
    private val login: LocalLogin,
) : UsernamePasswordAuthenticationFilter() {

    init {
        setAuthenticationManager(manager)
        setFilterProcessesUrl(PATH)
    }

    override fun attemptAuthentication(request: HttpServletRequest, response: HttpServletResponse): Authentication? {
        try {
            val input = ObjectMapper().readValue(request.inputStream, LoginRequest::class.java)
            return getAuthenticationManager().authenticate(
                UsernamePasswordAuthenticationToken(input.email, input.password, ArrayList<GrantedAuthority>()),
            )
        } catch (failure: InternalAuthenticationServiceException) {
            throw InternalAuthenticationServiceException("Local login authentication failed")
        } catch (failure: IOException) {
            throw RuntimeException("로그인 요청 처리 중 오류가 발생했습니다.")
        }
    }

    override fun successfulAuthentication(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
        authentication: Authentication,
    ) {
        val account = (authentication.principal as LocalLoginPrincipal).account
        val completed: LocalLogin.CompletedLogin
        try {
            completed = login.complete(account)
        } catch (failure: RuntimeException) {
            throw IllegalStateException("Local login completion failed: " + failure.javaClass.name)
        }
        response.addHeader(HttpHeaders.SET_COOKIE, RefreshCookies.issue(completed.refreshToken!!).toString())
        val body = LoginResponse(
            account.userId,
            account.email,
            account.name,
            account.role,
            account.careRegNo,
            completed.accessToken,
        )
        write(response, 200, "로그인에 성공했습니다.", body)
    }

    override fun unsuccessfulAuthentication(
        request: HttpServletRequest,
        response: HttpServletResponse,
        failure: AuthenticationException,
    ) {
        val message = when (failure) {
            is BadCredentialsException -> "이메일 또는 비밀번호가 일치하지 않습니다."
            is UsernameNotFoundException -> "존재하지 않는 사용자입니다."
            else -> "인증에 실패했습니다."
        }
        write(response, 401, message, null)
    }

    @JvmRecord
    data class LoginRequest(
        @field:Email @field:NotBlank val email: String?,
        @field:NotBlank val password: String?,
    ) {
        override fun toString(): String {
            return "LocalLoginRequest"
        }
    }

    /** The refresh token is deliberately absent: it travels only in the HttpOnly refresh cookie. */
    @JvmRecord
    private data class LoginResponse(
        val userId: Long,
        val email: String?,
        val name: String?,
        val role: String?,
        val careRegNo: String?,
        val accessToken: String?,
    )

    @JvmRecord
    private data class Envelope(
        val code: Int,
        val message: String?,
        val data: Any?,
    )

    companion object {
        const val PATH = "/api/v1/auth/login"

        private fun write(response: HttpServletResponse, code: Int, message: String, data: Any?) {
            response.status = code
            response.contentType = "application/json"
            response.characterEncoding = "UTF-8"
            response.writer.write(ObjectMapper().writeValueAsString(Envelope(code, message, data)))
        }
    }
}
