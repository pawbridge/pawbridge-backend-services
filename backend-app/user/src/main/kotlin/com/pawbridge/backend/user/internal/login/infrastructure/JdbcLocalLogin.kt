package com.pawbridge.backend.user.internal.login.infrastructure

import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.login.application.LocalLogin.Account
import com.pawbridge.backend.user.internal.login.application.LocalLogin.CompletedLogin
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import java.util.Optional

/** Authentication lookup precedes the session transaction; a new session is created for every login. */
class JdbcLocalLogin(
    private val jdbc: JdbcOperations,
    private val saves: TransactionOperations,
    private val tokens: JwtSessionTokens,
    private val sessions: JdbcAuthSessionStore,
) : LocalLogin {

    override fun findByEmail(email: String?): Optional<Account> {
        val found: List<Account> = jdbc.query(
            FIND_BY_EMAIL,
            RowMapper { row, _ ->
                Account(
                    row.getLong("user_id"),
                    row.getString("email"),
                    row.getString("name"),
                    row.getString("password"),
                    row.getString("role"),
                    row.getString("care_reg_no"),
                )
            },
            email,
        )
        if (found.size > 1) {
            throw IncorrectResultSizeDataAccessException(1, found.size)
        }
        return found.stream().findFirst()
    }

    override fun complete(account: Account): CompletedLogin {
        val claims = JwtSessionTokens.UserClaims(
            account.userId,
            account.email,
            account.name,
            account.role,
            account.careRegNo,
        )
        val accessToken = tokens.access(claims)
        val refreshToken: IssuedRefreshToken? = saves.execute { _ -> sessions.create(account.userId) }
        return CompletedLogin(accessToken, refreshToken)
    }

    private companion object {
        private const val FIND_BY_EMAIL =
            "SELECT user_id, email, name, password, role, care_reg_no FROM pawbridge_user.users WHERE lower(email) = lower(?)"
    }
}
