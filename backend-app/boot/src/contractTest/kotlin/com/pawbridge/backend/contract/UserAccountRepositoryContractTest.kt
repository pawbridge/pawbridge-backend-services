package com.pawbridge.backend.contract

import com.pawbridge.backend.user.internal.login.application.LocalLogin.Account
import com.pawbridge.backend.user.internal.login.infrastructure.JdbcLocalLogin
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException
import com.pawbridge.backend.user.internal.oauth.infrastructure.JdbcSocialLogin
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import com.pawbridge.backend.user.internal.session.infrastructure.RefreshTokens
import java.time.LocalDateTime
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.IncorrectResultSizeDataAccessException

/**
 * Account repositories called directly: the local login lookup, and the social login account lookup and creation, and
 * the session each starts.
 */
class UserAccountRepositoryContractTest : UserRepositoryContractBase() {
    @Autowired
    private lateinit var store: JdbcAuthSessionStore

    @Autowired
    private lateinit var tokens: JwtSessionTokens

    private val created: LocalDateTime = LocalDateTime.of(2026, 3, 5, 10, 11, 12, 345_678_000)

    // ---- Local login ------------------------------------------------------------------------------------------

    @Test
    fun `비밀번호가 있는 계정일 때__대소문자 무시 이메일로 찾으면__저장된 계정을 주고 없으면 빈 값이다`() {
        // given
        insertUser(9_932_001, email = "Login@Fixture.invalid", password = "ENCODED_LOGIN", role = "ROLE_SHELTER", careRegNo = "CARE-9")
        val login = JdbcLocalLogin(write, writeTransaction(), tokens, store)

        // when
        val found = login.findByEmail("login@FIXTURE.invalid")
        val missing = login.findByEmail("nobody@fixture.invalid")

        // then
        assertThat(found.get())
            .isEqualTo(Account(9_932_001, "Login@Fixture.invalid", "이름9932001", "ENCODED_LOGIN", "ROLE_SHELTER", "CARE-9"))
        assertThat(missing).isEmpty()
    }

    @Test
    fun `같은 이메일의 계정이 제공자별로 둘 있을 때__찾으면__하나를 고르지 않고 결과 수 오류를 던진다`() {
        // given
        insertUser(9_932_002, email = "both@fixture.invalid", provider = "LOCAL")
        insertUser(9_932_003, email = "both@fixture.invalid", provider = "GOOGLE", providerId = "g-both", nickname = "소셜닉")
        val login = JdbcLocalLogin(write, writeTransaction(), tokens, store)

        // when
        // then
        assertThatThrownBy { login.findByEmail("both@fixture.invalid") }
            .isInstanceOf(IncorrectResultSizeDataAccessException::class.java)
    }

    @Test
    fun `인증된 계정일 때__로그인을 마무리하면__액세스 토큰을 주고 해시만 저장된 새 세션을 만든다`() {
        // given
        insertUser(9_932_004, email = "session@fixture.invalid")
        val login = JdbcLocalLogin(write, writeTransaction(), tokens, store)
        val account = login.findByEmail("session@fixture.invalid").get()

        // when
        val completed = login.complete(account)

        // then
        assertThat(completed.accessToken).isNotBlank()
        val refresh = completed.refreshToken!!
        assertThat(RefreshTokens.wellFormed(refresh.value)).isTrue()
        assertThat(sessionCount(9_932_004, active = true)).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE token_hash = ?", RefreshTokens.hash(refresh.value!!)))
            .isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE token_hash = ?", refresh.value)).isZero()
    }

    // ---- Social login -----------------------------------------------------------------------------------------

    @Test
    @Tag("timezone-sensitive")
    fun `처음 오는 구글 회원일 때__불러오면__소셜 기본값으로 계정을 만들고 토큰은 발급하지 않는다`() {
        // given
        val social = socialLogin(nicknames = { "구글닉네임" })

        // when
        val user = social.load("google", google("sub-1", "Google@Fixture.invalid", "구글이름"))

        // then
        val stored = userRow(user.userId)
        assertThat(user.role).isEqualTo("ROLE_USER")
        assertThat(user.provider).isEqualTo("GOOGLE")
        assertThat(stored.get("email").asText()).isEqualTo("Google@Fixture.invalid")
        assertThat(stored.get("name").asText()).isEqualTo("구글이름")
        assertThat(stored.get("nickname").asText()).isEqualTo("구글닉네임")
        assertThat(stored.get("password").asText()).isEqualTo("OAuth2")
        assertThat(stored.get("provider").asText()).isEqualTo("GOOGLE")
        assertThat(stored.get("provider_id").asText()).isEqualTo("sub-1")
        assertThat(stored.get("role").asText()).isEqualTo("ROLE_USER")
        assertThat(stored.get("created_at").asText()).isEqualTo(storedWallClock(created))
        assertThat(stored.get("updated_at")).isEqualTo(stored.get("created_at"))
        assertThat(sessionCount(user.userId)).isZero()
    }

    @Test
    fun `이미 있는 구글 회원일 때__다시 불러오면__저장된 역할을 그대로 주고 행과 닉네임 생성을 건드리지 않는다`() {
        // given
        insertUser(9_935_001, email = "Admin@Fixture.invalid", provider = "GOOGLE", providerId = "sub-admin", role = "ROLE_ADMIN", careRegNo = "CARE-A")
        val before = ContractPostgres.snapshot()
        val social = socialLogin(nicknames = { error("nickname must not be generated") })

        // when
        val user = social.load("GOOGLE", google("other-sub", "admin@fixture.invalid", "바뀐 이름"))

        // then
        assertThat(user.userId).isEqualTo(9_935_001)
        assertThat(user.role).isEqualTo("ROLE_ADMIN")
        assertThat(user.careRegNo).isEqualTo("CARE-A")
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    @Test
    fun `같은 이메일의 로컬 계정이 있을 때__구글로 불러오면__연결하거나 만들지 않고 관리자 문의를 안내한다`() {
        // given
        insertUser(9_935_002, email = "same@fixture.invalid", provider = "LOCAL")
        val before = ContractPostgres.snapshot()
        val social = socialLogin(nicknames = { "unused" })

        // when
        // then
        assertThatThrownBy { social.load("google", google("sub-2", "same@fixture.invalid", "이름")) }
            .isInstanceOf(SocialLoginRejectedException::class.java)
            .hasMessageContaining("이미 해당 이메일로 가입된 계정이 있습니다")
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    @Test
    fun `처음 오는 네이버 회원일 때__불러오면__네이버 제공자와 제공자 아이디로 계정을 만든다`() {
        // given
        val social = socialLogin(nicknames = { "네이버닉" })

        // when
        val user = social.load("naver", naver("nv-1", "nv@fixture.invalid", "네이버이름"))

        // then
        val stored = userRow(user.userId)
        assertThat(user.provider).isEqualTo("NAVER")
        assertThat(stored.get("provider").asText()).isEqualTo("NAVER")
        assertThat(stored.get("provider_id").asText()).isEqualTo("nv-1")
        assertThat(stored.get("email").asText()).isEqualTo("nv@fixture.invalid")
    }

    @Test
    fun `이미 있는 네이버 제공자 아이디일 때__이메일이 바뀌어 불러오면__이메일을 믿지 않고 저장된 계정을 준다`() {
        // given
        insertUser(9_935_003, email = "old-nv@fixture.invalid", provider = "NAVER", providerId = "nv-known", nickname = "네이버옛")
        val before = ContractPostgres.snapshot()
        val social = socialLogin(nicknames = { error("nickname must not be generated") })

        // when
        val user = social.load("naver", naver("nv-known", "changed@fixture.invalid", "이름"))

        // then
        assertThat(user.userId).isEqualTo(9_935_003)
        assertThat(user.email).isEqualTo("old-nv@fixture.invalid")
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    @Test
    fun `같은 이메일의 다른 네이버 계정이나 로컬 계정이 있을 때__불러오면__이메일로 다시 연결하지 않고 거부한다`() {
        // given
        insertUser(9_935_004, email = "shared-nv@fixture.invalid", provider = "NAVER", providerId = "nv-first", nickname = "네이버첫")
        insertUser(9_935_005, email = "shared-local@fixture.invalid", provider = "LOCAL")
        val before = ContractPostgres.snapshot()
        val social = socialLogin(nicknames = { "unused" })

        // when
        // then
        assertThatThrownBy { social.load("naver", naver("nv-second", "shared-nv@fixture.invalid", "이름")) }
            .isInstanceOf(SocialLoginRejectedException::class.java)
            .hasMessageContaining("다른 소셜 계정")
        assertThatThrownBy { social.load("naver", naver("nv-third", "shared-local@fixture.invalid", "이름")) }
            .isInstanceOf(SocialLoginRejectedException::class.java)
            .hasMessageContaining("이미 해당 이메일로 가입된 계정이 있습니다")
        assertThat(ContractPostgres.snapshot() == before).isTrue()
    }

    @Test
    fun `생성한 닉네임이 그 사이 쓰였을 때__불러오면__롤백 뒤 한 번만 새 닉네임으로 다시 만든다`() {
        // given
        insertUser(9_935_006, nickname = "RaceName")
        val offered = ArrayDeque(listOf("racename", "FreshSocial"))
        val social = socialLogin(nicknames = { offered.removeFirst() })

        // when
        val user = social.load("google", google("sub-race", "race@fixture.invalid", "이름"))

        // then
        assertThat(userRow(user.userId).get("nickname").asText()).isEqualTo("FreshSocial")
        assertThat(offered).isEmpty()
    }

    @Test
    fun `닉네임이 두 번 모두 충돌할 때__불러오면__세 번째 시도 없이 제약 위반을 던지고 행은 늘지 않는다`() {
        // given
        insertUser(9_935_007, nickname = "AlwaysTaken")
        val before = userCount()
        var offered = 0
        val social = socialLogin(nicknames = { offered++; "alwaystaken" })

        // when
        // then
        assertThatThrownBy { social.load("google", google("sub-fail", "fail@fixture.invalid", "이름")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(offered).isEqualTo(2)
        assertThat(userCount()).isEqualTo(before)
    }

    @Test
    fun `이미 있는 소셜 회원일 때__로그인을 마무리하면__해시만 저장된 세션을 하나 만든다`() {
        // given
        insertUser(9_935_008, email = "done@fixture.invalid", provider = "GOOGLE", providerId = "sub-done", nickname = "마무리")
        val social = socialLogin(nicknames = { "unused" })
        val user = social.load("google", google("sub-done", "done@fixture.invalid", "이름"))

        // when
        val refresh = social.complete(user)

        // then
        assertThat(sessionCount(9_935_008, active = true)).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE token_hash = ?", RefreshTokens.hash(refresh.value!!)))
            .isOne()
    }

    private fun socialLogin(nicknames: () -> String): JdbcSocialLogin {
        return JdbcSocialLogin(write, writeTransaction(), store, Supplier { nicknames() }, Supplier { created })
    }

    private fun google(subject: String, email: String, name: String): MutableMap<String, Any?> =
        mutableMapOf("sub" to subject, "email" to email, "name" to name)

    private fun naver(id: String, email: String, name: String): MutableMap<String, Any?> =
        mutableMapOf("response" to mapOf("id" to id, "email" to email, "name" to name))
}
