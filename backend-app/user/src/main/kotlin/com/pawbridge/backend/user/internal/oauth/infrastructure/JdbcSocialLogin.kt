package com.pawbridge.backend.user.internal.oauth.infrastructure

import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore
import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.oauth.application.SocialLogin.SocialUser
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.transaction.support.TransactionOperations
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.TimeZone
import java.util.function.Supplier

/** Provider protocol I/O precedes this adapter; account creation and session creation stay separate. */
class JdbcSocialLogin(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
    private val sessions: JdbcAuthSessionStore,
    private val nicknames: Supplier<String>,
    private val now: Supplier<LocalDateTime>,
) : SocialLogin {

    constructor(jdbc: JdbcOperations, transactions: TransactionOperations, sessions: JdbcAuthSessionStore) :
        this(jdbc, transactions, sessions, defaultNicknames(jdbc), Supplier { LocalDateTime.now() })

    override fun load(registrationId: String?, attributes: MutableMap<String, Any?>): SocialUser {
        val profile = SocialProfile.from(registrationId, attributes)
        try {
            return loadAccount(profile)
        } catch (duplicate: DataIntegrityViolationException) {
            // The failed attempt must finish rollback and cleanup before the single permitted retry.
            return loadAccount(profile)
        }
    }

    private fun loadAccount(profile: SocialProfile): SocialUser =
        transactions.execute { _ ->
            val existing = findExisting(profile)
            if (existing.isNotEmpty()) {
                existing.first()
            } else {
                createAccount(profile)
            }
        }!!

    private fun createAccount(profile: SocialProfile): SocialUser {
        if (findUser(profile.email, "LOCAL").isNotEmpty()) {
            throw SocialLoginRejectedException("이미 해당 이메일로 가입된 계정이 있습니다. 관리자에게 문의해주세요.")
        }
        if (profile.identifiedByProviderId() && findUser(profile.email, profile.provider).isNotEmpty()) {
            // Same provider and email but another provider ID: never relink an account by email alone.
            throw SocialLoginRejectedException("이미 다른 소셜 계정에 연결된 이메일입니다. 관리자에게 문의해주세요.")
        }
        val nickname = nicknames.get()
        return insertUser(profile, nickname)
    }

    /**
     * Google keeps its original email lookup. Naver uses its per-application user ID because the
     * contact email can change and is not proof of account ownership.
     */
    private fun findExisting(profile: SocialProfile): List<SocialUser> {
        if (!profile.identifiedByProviderId()) {
            return findUser(profile.email, profile.provider)
        }
        val found: List<SocialUser> = jdbc.query(
            FIND_USER_BY_PROVIDER_ID,
            RowMapper(::user),
            profile.provider,
            profile.providerId,
        )
        if (found.size > 1) {
            throw IncorrectResultSizeDataAccessException(1, found.size)
        }
        return found
    }

    private fun findUser(email: String?, provider: String?): List<SocialUser> {
        val found: List<SocialUser> = jdbc.query(FIND_USER, RowMapper(::user), email, provider)
        if (found.size > 1) {
            throw IncorrectResultSizeDataAccessException(1, found.size)
        }
        return found
    }

    private fun insertUser(profile: SocialProfile, nickname: String): SocialUser {
        val email = profile.email
        val name = profile.name
        val timestamp = now.get()
        val generated = GeneratedKeyHolder()
        jdbc.update(
            PreparedStatementCreator { connection ->
                val statement = connection.prepareStatement(INSERT_USER, arrayOf("user_id"))
                try {
                    statement.setString(1, email)
                    statement.setString(2, name)
                    statement.setString(3, nickname)
                    statement.setString(4, "OAuth2")
                    statement.setString(5, "ROLE_USER")
                    statement.setString(6, null)
                    statement.setString(7, profile.provider)
                    statement.setString(8, profile.providerId)
                    statement.setTimestamp(9, Timestamp.valueOf(timestamp), utc())
                    statement.setTimestamp(10, Timestamp.valueOf(timestamp), utc())
                    statement
                } catch (failure: Throwable) {
                    closeFailed(statement, failure)
                    throw failure
                }
            },
            generated,
        )
        val id = generated.key ?: throw IllegalStateException("Social account ID was not returned")
        return SocialUser(id.toLong(), email, name, "ROLE_USER", null, profile.provider)
    }

    /** The session store locks the user row, so a concurrent account deletion cannot leave an orphan session. */
    override fun complete(user: SocialUser): IssuedRefreshToken =
        transactions.execute { _ -> sessions.create(user.userId) }!!

    private companion object {
        private const val FIND_USER =
            "SELECT user_id,email,name,role,care_reg_no,provider FROM pawbridge_user.users " +
                "WHERE lower(email)=lower(?) AND lower(provider)=lower(?)"
        private const val FIND_USER_BY_PROVIDER_ID =
            "SELECT user_id,email,name,role,care_reg_no,provider FROM pawbridge_user.users " +
                "WHERE provider = ? AND provider_id = ?"
        private const val INSERT_USER =
            "INSERT INTO pawbridge_user.users(email,name,nickname,password,role,care_reg_no,provider,provider_id," +
                "created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)"

        // One generator per adapter, shared by every call, like the bound method reference it replaces.
        private fun defaultNicknames(jdbc: JdbcOperations): Supplier<String> {
            val generator = SocialNicknameGenerator(JdbcUserNicknameStore(jdbc))
            return Supplier { generator.generateUniqueNickname() }
        }

        private fun user(row: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") number: Int): SocialUser =
            SocialUser(
                row.getLong("user_id"),
                row.getString("email"),
                row.getString("name"),
                row.getString("role"),
                row.getString("care_reg_no"),
                row.getString("provider"),
            )

        private fun closeFailed(statement: PreparedStatement, failure: Throwable) {
            try {
                statement.close()
            } catch (closing: SQLException) {
                failure.addSuppressed(closing)
            }
        }

        private fun utc(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    }
}
