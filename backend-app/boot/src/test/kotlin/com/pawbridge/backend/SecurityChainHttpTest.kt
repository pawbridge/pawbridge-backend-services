package com.pawbridge.backend

import com.pawbridge.backend.SecurityChainFixture.Companion.ADMIN_MESSAGE
import com.pawbridge.backend.SecurityChainFixture.Companion.BAD_CLAIMS
import com.pawbridge.backend.SecurityChainFixture.Companion.GENERIC_MESSAGE
import com.pawbridge.backend.SecurityChainFixture.Companion.bearer
import com.pawbridge.backend.SecurityChainFixture.Companion.rejectedTokens
import com.pawbridge.backend.SecurityChainFixture.Companion.send
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.security.core.context.SecurityContextHolder

/**
 * The cross-cutting contract of the main security chain, checked once for every route in [SecurityRoutes]
 * instead of once per endpoint test: who is let in, which token is refused, and that nothing behind the
 * chain runs for a refused request. Feature specific behavior stays in each endpoint's own test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityChainHttpTest {
    private val chain = SecurityChainFixture.open(SecurityRoutes.everythingOn)

    @AfterAll
    fun closeChain() {
        chain.close()
    }

    @AfterEach
    fun clearContext() {
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedRoutes")
    fun `보호 경로가 주어졌을 때__거부되는 토큰으로 요청하면__바인딩 전에 401과 토큰 메시지를 준다`(route: Route) {
        // given
        val malformed = route.malformedExample()
        val paths = listOfNotNull(route.example, malformed.takeIf { route.matches(route.method, it) })

        // when
        val outcomes = paths.flatMap { path ->
            rejectedTokens.map { (authorization, message) ->
                Triple(path, message, chain.call(send(route.method, path, authorization)))
            }
        }

        // then
        outcomes.forEach { (path, message, outcome) ->
            assertThat(outcome.status).describedAs("%s %s", route.method, path).isEqualTo(401)
            assertThat(outcome.isEnvelope(401, message)).describedAs("%s %s %s", route.method, path, outcome.body).isTrue()
            assertThat(outcome.reached).describedAs("%s %s", route.method, path).isZero()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminRoutes")
    fun `관리자 경로가 주어졌을 때__관리자가 아닌 서명 토큰과 위조 헤더로 요청하면__핸들러 전에 403을 준다`(route: Route) {
        // given
        val message = if (route.access == Access.ADMIN) ADMIN_MESSAGE else GENERIC_MESSAGE
        val callers = listOf("ROLE_USER", "ROLE_SHELTER", "ROLE_ADMINISTRATOR", "ROLE_ADMIN ", "ROLE_UNKNOWN", "", " ")
            .map { bearer(7, it) }

        // when
        val outcomes = callers.map { chain.call(send(route.method, route.example, it)) }
        val admin = chain.call(send(route.method, route.example, bearer(7, "ROLE_ADMIN")))

        // then
        outcomes.forEach { outcome ->
            assertThat(outcome.status).isEqualTo(403)
            assertThat(outcome.isEnvelope(403, message)).describedAs(outcome.body).isTrue()
            assertThat(outcome.reached).isZero()
        }
        assertThat(admin.status).isEqualTo(200)
        assertThat(admin.reached).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memberRoutes")
    fun `회원 경로가 주어졌을 때__어떤 역할이든 유효한 토큰으로 요청하면__핸들러에 닿고 다음 익명 요청은 신원을 물려받지 않는다`(route: Route) {
        // given
        val callers = listOf(
            bearer(7, "ROLE_USER"),
            bearer(8, "ROLE_SHELTER"),
            bearer(9, "ROLE_ADMIN"),
            bearer(10, "ROLE_FUTURE"),
            bearer(11, ""),
            bearer(12, " "),
        )

        // when
        val outcomes = callers.map { chain.call(send(route.method, route.example, it)) }
        val anonymousAfterwards = chain.call(send(route.method, route.example, null))

        // then
        outcomes.forEach { outcome ->
            assertThat(outcome.status).isEqualTo(200)
            assertThat(outcome.reached).isEqualTo(1)
            assertThat(outcome.hasSecurityHeaders()).isTrue()
        }
        assertThat(anonymousAfterwards.status).isEqualTo(401)
        assertThat(anonymousAfterwards.reached).isZero()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicRoutes")
    fun `공개 경로가 주어졌을 때__토큰이 없거나 잘못되어도 요청하면__토큰을 읽지 않고 핸들러에 닿는다`(route: Route) {
        // given
        val authorizations = rejectedTokens.map { it.first }

        // when
        val outcomes = authorizations.map { chain.call(send(route.method, route.example, it)) }

        // then
        outcomes.forEach { outcome ->
            assertThat(outcome.status).isEqualTo(200)
            assertThat(outcome.reached).isEqualTo(1)
            assertThat(outcome.hasSecurityHeaders()).isTrue()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allRoutes")
    fun `경로가 주어졌을 때__이웃 메서드와 더 깊은 경로와 옛 별칭으로 요청하면__가장 강한 토큰이어도 403으로 거부한다`(route: Route) {
        // given
        val neighbors = neighborsOf(route)
        val admin = bearer(7, "ROLE_ADMIN")

        // when
        val outcomes = neighbors.map { (method, path) -> Triple(method, path, chain.call(send(method, path, admin))) }
        val anonymous = neighbors.map { (method, path) -> chain.call(send(method, path, null)) }

        // then
        assertThat(neighbors).isNotEmpty()
        outcomes.forEach { (method, path, outcome) ->
            assertThat(outcome.status).describedAs("%s %s", method, path).isEqualTo(403)
            assertThat(outcome.isEnvelope(403, GENERIC_MESSAGE)).describedAs("%s %s %s", method, path, outcome.body).isTrue()
            assertThat(outcome.reached).describedAs("%s %s", method, path).isZero()
        }
        anonymous.forEach { outcome ->
            assertThat(outcome.status).isEqualTo(403)
            assertThat(outcome.isEnvelope(403, GENERIC_MESSAGE)).isTrue()
            assertThat(outcome.reached).isZero()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extraDeniedRoutes")
    fun `숫자나 UUID만 받는 경로가 주어졌을 때__형식이 다른 값으로 요청하면__가장 강한 토큰이어도 403으로 거부한다`(request: String) {
        // given
        val (method, path) = request.split(" ", limit = 2)

        // when
        val outcome = chain.call(send(method, path, bearer(7, "ROLE_ADMIN")))

        // then
        assertThat(outcome.status).isEqualTo(403)
        assertThat(outcome.reached).isZero()
    }

    @Test
    fun `쪽지 스트림이 주어졌을 때__만료 없는 토큰으로 열면__다른 경로와 달리 401로 거부한다`() {
        // given
        val withoutExpiry = "Bearer " + io.jsonwebtoken.Jwts.builder()
            .claim("userId", 7)
            .claim("role", "ROLE_USER")
            .signWith(F3TestInputs.KEY)
            .compact()

        // when
        val stream = chain.call(send("GET", "/api/v1/notes/stream", withoutExpiry))
        val list = chain.call(send("GET", "/api/v1/notes", withoutExpiry))

        // then
        assertThat(stream.status).isEqualTo(401)
        assertThat(stream.isEnvelope(401, BAD_CLAIMS)).isTrue()
        assertThat(stream.reached).isZero()
        assertThat(list.status).isEqualTo(200)
        assertThat(list.reached).isEqualTo(1)
    }

    @Test
    fun `지운 이메일 인증 경로가 주어졌을 때__예전 플래그를 켜고 요청해도__가장 강한 토큰이어도 403으로 거부한다`() {
        // given
        val oldFlags = listOf("email-verification", "local-signup", "password-reset", "user-password")
            .associate { "pawbridge.$it.enabled" to "true" }
        val admin = bearer(7, "ROLE_ADMIN")

        // when
        val outcomes = SecurityChainFixture.open(SecurityRoutes.everythingOn + oldFlags).use { retired ->
            retiredEmailRoutes.flatMap { request ->
                val (method, path) = request.split(" ", limit = 2)
                listOf(request to retired.call(send(method, path, admin)), request to retired.call(send(method, path, null)))
            }
        }

        // then
        outcomes.forEach { (request, outcome) ->
            assertThat(outcome.status).describedAs(request).isEqualTo(403)
            assertThat(outcome.reached).describedAs(request).isZero()
        }
    }

    companion object {
        private val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        @JvmStatic
        fun allRoutes(): List<Route> = SecurityRoutes.routes

        @JvmStatic
        fun protectedRoutes(): List<Route> = SecurityRoutes.routes.filter { it.access != Access.PUBLIC }

        @JvmStatic
        fun adminRoutes(): List<Route> = SecurityRoutes.routes.filter {
            it.access == Access.ADMIN || it.access == Access.ADMIN_GENERIC
        }

        @JvmStatic
        fun memberRoutes(): List<Route> = SecurityRoutes.routes.filter { it.access == Access.MEMBER }

        @JvmStatic
        fun publicRoutes(): List<Route> = SecurityRoutes.routes.filter { it.access == Access.PUBLIC }

        // Email login, signup, verification codes and password reset/change were removed (members sign in with Google
        // or Naver only); their old addresses must stay closed.
        private val retiredEmailRoutes = listOf(
            "POST /api/v1/email/send",
            "POST /api/v1/email/verify",
            "POST /api/v1/users/signup",
            "POST /api/v1/auth/password/reset-request",
            "POST /api/v1/auth/password/reset",
            "PUT /api/v1/users/me/password",
        )

        @JvmStatic
        fun extraDeniedRoutes(): List<String> = listOf(
            "GET /api/v1/animals/abc",
            "GET /api/v1/animals/7/similar/extra",
            "GET /api/v1/animals/abc/similar",
            "GET /api/v1/shelters/abc",
            "GET /api/v1/shelters/abc/observations",
            "GET /api/v1/chats/abc",
            "GET /api/v1/chats/abc/messages",
            "PUT /api/v1/chats/abc/read",
            "DELETE /api/v1/chats/abc/visibility",
            "GET /api/v1/posts/read/7/extra",
            "PUT /api/v1/posts/read/7",
            "GET /api/v1/notes/stream/extra",
            "GET /api/v1/admin/users/7/extra",
            "POST /api/v1/orders/7",
            "GET /api/v1/animals/-1",
            "GET /api/v1/shelters/-1",
            "GET /api/v1/shelters/-1/observations",
            "GET /api/v1/users/internal/7/nickname",
            "GET /api/v1/users/internal/7/contact",
            "GET /api/v1/users/internal/7/state",
            "GET /internal/private-notes/members/7",
            "GET /api/v1/users/7",
            "PUT /api/v1/users/7/nickname",
            "PUT /api/v1/users/7/password",
            "GET /api/v1/users/me/7",
            "GET /api/v1/admin/comments/7",
            "GET /api/v1/orders/7/status",
            "POST /api/v1/orders/7/cancel",
            "GET /api/v1/products/7/skus",
            "GET /api/v1/internal/places/refresh",
            "POST /api/v1/carts/reset",
            "POST /api/v1/auth/login/extra",
            "GET /error",
            "POST /api/v1/v1/batch/apms/sync",
            "POST /api/v1/auth/login",
            "GET /actuator/info",
            "GET /actuator/prometheus",
        )

        private fun neighborsOf(route: Route): List<Pair<String, String>> {
            val everything = SecurityRoutes.everythingOn
            val candidates = buildList {
                methods.forEach { add(it to route.example) }
                methods.forEach { add(it to route.example + "/extra") }
                methods.forEach { add(it to route.example + "/") }
                methods.forEach { add(it to route.example + "-extra") }
                val parent = route.example.substringBeforeLast('/')
                methods.forEach { add(it to parent) }
                methods.forEach { add(it to "$parent/unknown") }
                if (route.example.startsWith("/api/v1/")) {
                    add(route.method to route.example.replaceFirst("/api/v1/", "/api/"))
                }
            }
            return candidates.filter { (method, path) -> !SecurityRoutes.isOpen(everything, method, path) }
        }
    }
}
