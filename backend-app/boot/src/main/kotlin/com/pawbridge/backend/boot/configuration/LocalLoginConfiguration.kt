package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.security.GatewayCors
import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.login.infrastructure.JdbcLocalLogin
import com.pawbridge.backend.user.internal.login.web.LocalLoginFilter
import com.pawbridge.backend.user.internal.login.web.LocalLoginPrincipal
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.filter.CorsFilter

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.auth", name = ["local-enabled"], havingValue = "true", matchIfMissing = false)
class LocalLoginConfiguration {
    @Bean
    fun localLogin(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        tokens: JwtSessionTokens,
        sessions: JdbcAuthSessionStore,
        @Value("\${pawbridge.auth.sessions-enabled:false}") sessionsEnabled: Boolean,
    ): LocalLogin {
        if (!sessionsEnabled) {
            throw IllegalArgumentException("Local login requires explicitly enabled auth sessions")
        }
        return JdbcLocalLogin(jdbc, TransactionTemplate(manager), tokens, sessions)
    }

    @Bean
    @Order(0)
    @Throws(Exception::class)
    fun localLoginSecurity(
        http: HttpSecurity,
        login: LocalLogin,
        @Value(GatewayCors.ORIGIN_PATTERNS_VALUE) corsOriginPatterns: String,
    ): SecurityFilterChain {
        val users = UserDetailsService { email ->
            login.findByEmail(email)
                .map { LocalLoginPrincipal(it) }
                .orElseThrow { UsernameNotFoundException("사용자를 찾을 수 없습니다: $email") }
        }

        val provider = DaoAuthenticationProvider()
        provider.setUserDetailsService(users)
        provider.setPasswordEncoder(BCryptPasswordEncoder())
        val manager = ProviderManager(provider)

        val securityContextRepository = RequestAttributeSecurityContextRepository()
        val localLoginFilter = LocalLoginFilter(manager, login)

        return http
            .securityMatcher(AntPathRequestMatcher(LocalLoginFilter.PATH))
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { context ->
                context
                    .securityContextRepository(securityContextRepository)
                    .requireExplicitSave(true)
            }
            .authenticationManager(manager)
            .authorizeHttpRequests { requests -> requests.anyRequest().permitAll() }
            // The browser sends a preflight OPTIONS before the cross-origin login POST. CorsFilter sits before
            // LocalLoginFilter, so the preflight is answered here and never reaches authentication.
            .addFilterAt(GatewayCors.filter(corsOriginPatterns), CorsFilter::class.java)
            .addFilterBefore(localLoginFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
    }
}
