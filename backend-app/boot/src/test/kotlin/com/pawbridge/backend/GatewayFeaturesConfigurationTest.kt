package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.GatewayFeaturesConfiguration
import com.pawbridge.backend.boot.security.GatewayCors
import com.pawbridge.backend.boot.security.LostSearchLimitsFilter
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Properties

class GatewayFeaturesConfigurationTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(GatewayFeaturesConfiguration::class.java)

    @Test
    fun `기본 설정일 때__기동하면__분실 검색 한도 필터가 없다`() {
        // given
        // 클래스의 runner는 설정 클래스만 올린 기본 상태다

        // when
        runner.run { context ->
            // then
            assertThat(context).doesNotHaveBean("lostSearchLimitsFilter")
        }
    }

    @Test
    fun `분실 검색을 켰을 때__기동하면__한도 필터가 보안 뒤에 등록된다`() {
        // given
        val enabled = runner.withPropertyValues("pawbridge.animal-lost-search.enabled=true")

        // when
        enabled.run { context ->
            // then
            val registration = context.getBean("lostSearchLimitsFilter") as FilterRegistrationBean<*>
            assertThat(registration.filter).isInstanceOf(LostSearchLimitsFilter::class.java)
            assertThat(registration.order).isGreaterThan(-100)
            assertThat(registration.urlPatterns).contains(LostSearchLimitsFilter.PATH)
        }
    }

    @Test
    fun `속성이 없을 때__분실 검색을 요청하면__기본 크기와 분당 횟수 한도가 적용된다`() {
        // given
        val enabled = runner.withPropertyValues("pawbridge.animal-lost-search.enabled=true")

        enabled.run { context ->
            val filter = limitsFilter(context)

            // when, then
            assertThat(search(filter, 6_291_456).status).isEqualTo(200)
            assertThat(search(filter, 6_291_457).status).isEqualTo(413)
            repeat(29) { assertThat(search(filter, 1).status).isEqualTo(200) }
            assertThat(search(filter, 1).status).isEqualTo(429)
        }
    }

    @Test
    fun `속성이 없을 때__분실 검색이 겹치면__기본 동시 처리 열한 건(파이썬 줄 크기)까지만 받는다`() {
        // given
        val enabled = runner.withPropertyValues("pawbridge.animal-lost-search.enabled=true")

        enabled.run { context ->
            // when, then
            assertThat(nestedStatuses(limitsFilter(context), depth = 12)).containsExactly(*(List(11) { 200 } + 503).toTypedArray())
        }
    }

    @Test
    fun `분실 검색 속성을 줬을 때__분실 검색을 요청하면__기본값 대신 준 한도가 적용된다`() {
        // given
        val overridden = runner.withPropertyValues(
            "pawbridge.animal-lost-search.enabled=true",
            "lost-search.max-request-bytes=100",
            "lost-search.max-concurrent=5",
            "lost-search.requests-per-minute=7",
            "lost-search.timeout-seconds=9",
        )

        overridden.run { context ->
            val filter = limitsFilter(context)

            // when, then
            assertThat(search(filter, 100).status).isEqualTo(200)
            assertThat(search(filter, 101).status).isEqualTo(413)
            assertThat(nestedStatuses(filter, depth = 6).takeLast(2)).containsExactly(200, 503)
        }
    }

    @Test
    fun `런타임 속성일 때__읽으면__health info prometheus만 루프백 별도 포트로 노출한다`() {
        // given
        val properties = Properties()

        // when
        javaClass.getResourceAsStream("/application.properties").use { input -> properties.load(input) }

        // then
        assertThat(properties.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info,prometheus")
        assertThat(properties.getProperty("management.server.port")).isEqualTo("\${PAWBRIDGE_MANAGEMENT_PORT:9464}")
        assertThat(properties.getProperty("management.server.address"))
            .isEqualTo("\${PAWBRIDGE_MANAGEMENT_ADDRESS:127.0.0.1}")
        assertThat(properties.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true")
        assertThat(properties.getProperty("management.endpoint.health.show-details")).isEqualTo("never")
        assertThat(properties.getProperty("management.health.mail.enabled")).isEqualTo("false")
        assertThat(properties.getProperty("pawbridge.legacy-api-paths.enabled")).isNull()
        assertThat(properties.getProperty("server.address")).isEqualTo("127.0.0.1")
    }

    @Test
    fun `기본 출처일 때__읽으면__원래 게이트웨이 목록과 같다`() {
        // given
        val origins = GatewayCors.DEFAULT_ORIGIN_PATTERNS

        // when
        val split = origins.split(",")

        // then
        assertThat(split).containsExactly(
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "https://pawbridge.kr",
            "https://www.pawbridge.kr",
            "https://*.pawbridge.kr",
        )
    }

    private fun limitsFilter(context: AssertableApplicationContext): LostSearchLimitsFilter {
        val registration = context.getBean("lostSearchLimitsFilter") as FilterRegistrationBean<*>
        return registration.filter as LostSearchLimitsFilter
    }

    private fun search(filter: LostSearchLimitsFilter, bytes: Int): MockHttpServletResponse {
        return search(filter, bytes, MockFilterChain())
    }

    private fun search(filter: LostSearchLimitsFilter, bytes: Int, chain: FilterChain): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", LostSearchLimitsFilter.PATH)
        request.contentType = "multipart/form-data; boundary=x"
        request.setContent(ByteArray(bytes))
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, chain)
        return response
    }

    /** Statuses of [depth] searches that are all still running when the next one starts, outermost first. */
    private fun nestedStatuses(filter: LostSearchLimitsFilter, depth: Int): List<Int> {
        val statuses = arrayOfNulls<Int>(depth)

        fun start(level: Int) {
            val inner = FilterChain { _, _ -> if (level + 1 < depth) start(level + 1) }
            statuses[level] = search(filter, 1, inner).status
        }

        start(0)
        return statuses.map { it!! }
    }
}
