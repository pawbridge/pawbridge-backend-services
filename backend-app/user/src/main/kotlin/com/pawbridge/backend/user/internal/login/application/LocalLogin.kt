package com.pawbridge.backend.user.internal.login.application

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import java.util.Optional

interface LocalLogin {
    fun findByEmail(email: String?): Optional<Account>

    fun complete(account: Account): CompletedLogin

    @JvmRecord
    data class Account(
        val userId: Long,
        val email: String?,
        val name: String?,
        val password: String?,
        val role: String?,
        val careRegNo: String?,
    ) {
        override fun toString(): String {
            return "LocalLogin.Account"
        }
    }

    /** The access token goes in the response body; the refresh token only in the refresh cookie. */
    @JvmRecord
    data class CompletedLogin(
        val accessToken: String?,
        val refreshToken: IssuedRefreshToken?,
    ) {
        override fun toString(): String {
            return "LocalLogin.CompletedLogin"
        }
    }
}
