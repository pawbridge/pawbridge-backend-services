package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The real application on two real Tomcat listeners: the main port and the separate management port.
 * Spring Boot test slices switch metric export off by default, hence [AutoConfigureObservability].
 */
@AutoConfigureObservability
@SpringBootTest(
    classes = [PawBridgeApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=0",
        "pawbridge.auth.sessions-enabled=true",
        "pawbridge.auth.local-enabled=true",
        "jwt.access-token-expiration=3600000",
        "jwt.refresh-token-expiration=86400000",
    ],
)
@ContextConfiguration(classes = [PawBridgeApplication::class], initializers = [PrometheusManagementPortHttpTest.Inputs::class])
class PrometheusManagementPortHttpTest {
    class Inputs : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            val values = F3TestInputs.values()
            val factory = context.beanFactory
            factory.registerSingleton(
                "readDatabaseSettings",
                ReadDatabaseSettings.fromEnvironment { name -> values[name] },
            )
            factory.registerSingleton(
                "postWriteSettings",
                PostWriteSettings.fromEnvironment { name -> values[name] },
            )
        }
    }

    @LocalServerPort
    var mainPort: Int = 0

    @LocalManagementPort
    var managementPort: Int = 0

    private fun scrape(): String {
        val response = send(managementPort, "GET", "/actuator/prometheus")
        assertThat(response.statusCode()).isEqualTo(200)
        return response.body()
    }

    @Test
    fun `관리 포트가 주어졌을 때__프로메테우스를 스크랩하면__JVM과 풀 지표와 함께 200을 돌려준다`() {
        // given
        // when
        val response = send(managementPort, "GET", "/actuator/prometheus")

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain")
        assertThat(response.body()).contains("jvm_memory_used_bytes")
        assertThat(response.body()).contains("hikaricp_connections{")
        assertThat(response.body()).contains("pool=\"pawbridge-db\"")
    }

    @Test
    fun `관리 포트가 주어졌을 때__스크랩하면__프로세스와 톰캣과 로그백 지표가 있다`() {
        // given
        // when
        val body = scrape()

        // then
        assertThat(body).contains("process_cpu_usage")
        assertThat(body).contains("jvm_threads_live_threads")
        assertThat(body).contains("tomcat_threads_busy_threads")
        assertThat(body).contains("logback_events_total")
    }

    @Test
    fun `경로 변수가 있는 요청이 있을 때__스크랩하면__uri 태그는 값이 아니라 템플릿이다`() {
        // given
        val main = send(mainPort, "GET", "/api/v1/posts/read/not-a-number-987654321")
        // The non-numeric id fails argument conversion inside MVC, without a database call; it is not denied by security.
        assertThat(main.statusCode()).isNotEqualTo(403)

        // when
        val body = scrape()

        // then
        assertThat(body).contains("http_server_requests_seconds_count")
        assertThat(body).contains("uri=\"/api/v1/posts/read/{postId}\"")
        assertThat(body).doesNotContain("987654321")
    }

    @Test
    fun `일치하지 않는 경로가 있을 때__스크랩하면__경로마다 시계열을 만들지 않는다`() {
        // given
        send(mainPort, "GET", "/no-such-path-123456789")
        send(managementPort, "GET", "/no-such-path-123456789")

        // when
        val body = scrape()

        // then
        assertThat(body).doesNotContain("123456789")
    }

    @ParameterizedTest
    @CsvSource("/actuator/prometheus", "/actuator/metrics", "/actuator/info", "/actuator/env", "/actuator")
    fun `메인 포트가 주어졌을 때__액추에이터 경로를 요청하면__프로메테우스를 포함한 모든 엔드포인트를 거부한다`(
        path: String,
    ) {
        // given
        // when
        val response = send(mainPort, "GET", path)

        // then
        assertThat(response.statusCode()).isEqualTo(403)
        assertThat(response.body()).doesNotContain("jvm_memory_used_bytes")
    }

    @Test
    fun `메인 포트가 주어졌을 때__헬스를 요청하면__액추에이터를 거기서 제공하지 않는다`() {
        // given
        // The main chain still permits health (it matters if management.server.port is set equal to server.port),
        // but with a separate management port nothing is mapped on the main port.

        // when
        val response = send(mainPort, "GET", "/actuator/health")

        // then
        assertThat(response.statusCode()).isEqualTo(404)
    }

    @ParameterizedTest
    @CsvSource("GET,/actuator/health/liveness", "GET,/actuator/health/readiness")
    fun `관리 포트가 주어졌을 때__프로브를 요청하면__공개되어 있고 UP이다`(method: String, path: String) {
        // given
        // when
        val response = send(managementPort, method, path)

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"status\":\"UP\"")
    }

    @Test
    fun `관리 포트가 주어졌을 때__전체 헬스를 요청하면__세부 정보 없이 공개된다`() {
        // given
        // The test database is unreachable by design, so the overall status may be DOWN (503); it must not be 403/404.

        // when
        val response = send(managementPort, "GET", "/actuator/health")

        // then
        assertThat(response.statusCode()).isIn(200, 503)
        assertThat(response.body()).contains("\"status\"")
        assertThat(response.body()).doesNotContain("components")
    }

    @Test
    fun `로컬 로그인이 켜져 있을 때__자격 증명을 보내면__메인 포트만 로그인 체인에 닿는다`() {
        // given
        // when
        val main = send(mainPort, "POST", "/api/v1/auth/login")
        val management = send(managementPort, "POST", "/api/v1/auth/login")

        // then
        // The login chain permits everyone and runs its own filter, so on the main port it answers with something
        // other than the security denial; on the management port the dedicated chain must win.
        assertThat(main.statusCode()).isNotEqualTo(403)
        assertThat(management.statusCode()).isEqualTo(403)
    }

    @ParameterizedTest
    @CsvSource(
        "GET,/actuator/info", "GET,/actuator/env", "GET,/actuator/metrics", "GET,/actuator/beans",
        "GET,/actuator/heapdump", "GET,/actuator", "GET,/actuator/prometheus/", "GET,/actuator/health-x",
        "POST,/actuator/prometheus", "POST,/actuator/health", "DELETE,/actuator/prometheus",
        "GET,/api/v1/animals", "GET,/api/v1/posts/read/1", "POST,/api/v1/auth/login",
        "GET,/oauth2/authorization/google", "GET,/login/oauth2/code/google", "GET,/ws/chat", "GET,/",
        "GET,/error", "GET,/no-such-path",
    )
    fun `관리 포트가 주어졌을 때__그 밖의 것을 요청하면__메인 체인이 허용하는 경로도 거부한다`(
        method: String,
        path: String,
    ) {
        // given
        // when
        val response = send(managementPort, method, path)

        // then
        assertThat(response.statusCode()).`as`(method + " " + path).isEqualTo(403)
    }

    companion object {
        private fun send(port: Int, method: String, path: String): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(15))
                .build()
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        }
    }
}
