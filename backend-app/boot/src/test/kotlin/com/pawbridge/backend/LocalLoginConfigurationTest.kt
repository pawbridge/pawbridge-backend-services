package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.LocalLoginConfiguration
import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.login.web.LocalLoginFilter
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import io.mockk.justRun
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.SecurityFilterChain
import java.util.function.Supplier

class LocalLoginConfigurationTest {
    // Spring runs the InitializingBean callback when it registers these as beans, so a strict mock must accept it.
    private fun jdbcTemplate(): JdbcTemplate {
        val bean = mockk<JdbcTemplate>()
        justRun { bean.afterPropertiesSet() }
        return bean
    }

    // Spring runs the InitializingBean callback when it registers these as beans, so a strict mock must accept it.
    private fun transactionManager(): JdbcTransactionManager {
        val bean = mockk<JdbcTransactionManager>()
        justRun { bean.afterPropertiesSet() }
        return bean
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `로컬 로그인이 꺼졌을 때__입력 없이 조립하면__로그인과 체인을 등록하지 않는다`(flag: String) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(LocalLoginConfiguration::class.java)
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.auth.local-enabled=false")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(LocalLogin::class.java)
                .doesNotHaveBean(SecurityFilterChain::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jdbc", "manager", "tokens", "sessions"])
    fun `켜졌지만 공유 입력 하나가 없을 때__조립하면__대체물을 만들지 않고 실패한다`(
        missing: String,
    ) {
        // given
        val runner = inputs(missing)

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `세션 플래그를 명시적으로 켜지 않았을 때__로컬 로그인을 켜고 조립하면__인증 전에 실패한다`() {
        // given
        val runner = inputs("").withPropertyValues("pawbridge.auth.sessions-enabled=false")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("Local login requires explicitly enabled auth sessions")
        }
    }

    @Test
    fun `공유 입력이 모두 있을 때__조립하면__로그인 경로만 체인이 받고 전역 인증 빈은 없다`() {
        // given
        val runner = inputs("")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            val chain = context.getBean(SecurityFilterChain::class.java) as DefaultSecurityFilterChain
            for (method in arrayOf("POST", "GET", "PATCH")) {
                val request = MockHttpServletRequest(method, LocalLoginFilter.PATH)
                request.servletPath = LocalLoginFilter.PATH
                assertThat(chain.matches(request)).isTrue()
            }
            val other = MockHttpServletRequest("POST", LocalLoginFilter.PATH + "/extra")
            other.servletPath = LocalLoginFilter.PATH + "/extra"
            assertThat(chain.matches(other)).isFalse()
            assertThat(context)
                .doesNotHaveBean(LocalLoginFilter::class.java)
                .doesNotHaveBean(AuthenticationManager::class.java)
                .doesNotHaveBean(AuthenticationProvider::class.java)
                .doesNotHaveBean(PasswordEncoder::class.java)
                .doesNotHaveBean(UserDetailsService::class.java)
        }
    }

    private fun inputs(missing: String): WebApplicationContextRunner {
        var runner = WebApplicationContextRunner()
            .withUserConfiguration(LocalLoginConfiguration::class.java, SecurityInputs::class.java)
            .withPropertyValues("pawbridge.auth.local-enabled=true", "pawbridge.auth.sessions-enabled=true")
        if (missing != "jdbc") {
            runner = runner.withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbcTemplate() })
        }
        if (missing != "manager") {
            runner = runner.withBean(
                "writeTransactionManager",
                JdbcTransactionManager::class.java,
                Supplier { transactionManager() },
            )
        }
        if (missing != "tokens") {
            runner = runner.withBean(JwtSessionTokens::class.java, Supplier { mockk<JwtSessionTokens>() })
        }
        if (missing != "sessions") {
            runner = runner.withBean(JdbcAuthSessionStore::class.java, Supplier { mockk<JdbcAuthSessionStore>() })
        }
        return runner
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    class SecurityInputs
}
