package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoCurationException
import com.pawbridge.backend.community.internal.homevideo.domain.CandidateVideo
import com.pawbridge.backend.community.internal.homevideo.domain.CurationCriteria
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OpenAiCandidateJudgeTest {
    private lateinit var server: HttpServer
    private lateinit var http: HttpClient
    private val mapper = ObjectMapper()
    private val responses = ArrayDeque<Pair<Int, String>>()
    private val requests = ArrayList<Request>()
    private val pauses = ArrayList<Duration>()

    @BeforeEach
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1") { exchange ->
            val body = String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            requests.add(Request(exchange.requestURI.toString(), exchange.requestHeaders.getFirst("Authorization"), body))
            val (status, payload) = responses.removeFirst()
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()
        http = HttpClient.newHttpClient()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        http.close()
    }

    @Test
    fun `판정을 받았을 때__판정하면__영상마다 맞음 여부를 돌려주고 키는 헤더로만 보낸다`() {
        // given
        responses.add(200 to answer("""[{"id":"Candidate01","fit":true,"reason":"입양 경험담"},""" +
            """{"id":"Candidate02","fit":false,"reason":"기관 홍보"}]"""))

        // when
        val verdicts = judge().judge(listOf(video("Candidate01"), video("Candidate02")))

        // then
        assertThat(verdicts).isEqualTo(mapOf("Candidate01" to true, "Candidate02" to false))
        val request = requests.single()
        assertThat(request.path).isEqualTo("/v1/chat/completions")
        assertThat(request.key).isEqualTo("Bearer synthetic-key")
        assertThat(request.body).doesNotContain("synthetic-key")
    }

    @Test
    fun `판정을 요청할 때__질문에__목적과 기준과 후보를 담고 JSON 목록으로 답하게 한다`() {
        // given
        responses.add(200 to answer("[]"))

        // when
        judge().judge(listOf(video("Candidate01").copy(description = "가".repeat(400))))

        // then
        val body = mapper.readTree(requests.single().body)
        assertThat(body.path("model").asText()).isEqualTo("gpt-6-luna")
        val prompt = body.path("messages").path(0).path("content").asText()
        assertThat(prompt).contains(CurationCriteria.PURPOSE, CurationCriteria.FIT, CurationCriteria.UNFIT[0])
        assertThat(prompt).contains("Candidate01", "가".repeat(300)).doesNotContain("가".repeat(301))
        val format = body.path("response_format")
        assertThat(format.path("type").asText()).isEqualTo("json_schema")
        assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue()
        assertThat(format.path("json_schema").path("schema").path("properties").path("verdicts").path("type").asText())
            .isEqualTo("array")
    }

    @Test
    fun `묻지 않은 ID나 참거짓이 아닌 답이 올 때__판정하면__그 답은 버린다`() {
        // given
        responses.add(200 to answer("""[{"id":"Candidate01","fit":"yes","reason":""},""" +
            """{"id":"Stranger001","fit":true,"reason":""},{"id":"Candidate02","fit":true,"reason":""}]"""))

        // when
        val verdicts = judge().judge(listOf(video("Candidate01"), video("Candidate02")))

        // then
        assertThat(verdicts).isEqualTo(mapOf("Candidate02" to true))
    }

    @Test
    fun `일시 과부하가 두 번 이어질 때__판정하면__잠시 쉬고 다시 보내 세 번째 답을 쓴다`() {
        // given
        responses.add(503 to "{}")
        responses.add(503 to "{}")
        responses.add(200 to answer("""[{"id":"Candidate01","fit":true,"reason":""}]"""))

        // when
        val verdicts = judge().judge(listOf(video("Candidate01")))

        // then
        assertThat(verdicts).isEqualTo(mapOf("Candidate01" to true))
        assertThat(requests).hasSize(3)
        assertThat(pauses).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5))
    }

    @Test
    fun `과부하가 세 번 이어질 때__판정하면__키 없이 실패한다`() {
        // given
        repeat(3) { responses.add(503 to "synthetic-key") }

        // when
        val thrown = assertThatThrownBy { judge().judge(listOf(video("Candidate01"))) }

        // then
        thrown.isInstanceOf(HomeVideoCurationException::class.java)
            .hasMessageContaining("HTTP 503")
            .hasMessageNotContaining("synthetic-key")
        assertThat(requests).hasSize(3)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 403, 429])
    fun `요청 한도나 요청 오류일 때__판정하면__다시 보내지 않고 실패한다`(status: Int) {
        // given
        responses.add(status to "{}")

        // when, then
        assertThatThrownBy { judge().judge(listOf(video("Candidate01"))) }
            .isInstanceOf(HomeVideoCurationException::class.java)
        assertThat(requests).hasSize(1)
        assertThat(pauses).isEmpty()
    }

    @Test
    fun `답이 길이 상한에 걸려 잘렸을 때__판정하면__일부만 쓰지 않고 실패한다`() {
        // given
        val truncated = """{"choices":[{"finish_reason":"length","message":{"content":"{\"verdicts\":["}}]}"""
        responses.add(200 to truncated)

        // when, then
        assertThatThrownBy { judge().judge(listOf(video("Candidate01"))) }
            .isInstanceOf(HomeVideoCurationException::class.java)
            .hasMessageContaining("잘렸")
    }

    @Test
    fun `답이 JSON 목록이 아닐 때__판정하면__실패한다`() {
        // given
        responses.add(200 to answer("판정할 수 없습니다"))

        // when, then
        assertThatThrownBy { judge().judge(listOf(video("Candidate01"))) }
            .isInstanceOf(HomeVideoCurationException::class.java)
    }

    @Test
    fun `모델이 판정을 거절했을 때__판정하면__실패한다`() {
        // given
        val refused = """{"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"할 수 없습니다"}}]}"""
        responses.add(200 to refused)

        // when, then
        assertThatThrownBy { judge().judge(listOf(video("Candidate01"))) }
            .isInstanceOf(HomeVideoCurationException::class.java)
            .hasMessageContaining("거절")
    }

    @Test
    fun `키가 비었을 때__판정하면__요청을 보내지 않고 실패한다`() {
        // given
        val judge = OpenAiCandidateJudge(mapper, " ", "gpt-6-luna", http, endpoint())

        // when, then
        assertThatThrownBy { judge.judge(listOf(video("Candidate01"))) }
            .isInstanceOf(HomeVideoCurationException::class.java)
        assertThat(requests).isEmpty()
    }

    @Test
    fun `모델 이름이 주소를 바꿀 수 있는 모양일 때__판정하면__요청을 보내지 않고 실패한다`() {
        // given
        val judge = OpenAiCandidateJudge(mapper, "synthetic-key", "../other", http, endpoint())

        // when, then
        assertThatThrownBy { judge.judge(listOf(video("Candidate01"))) }
            .isInstanceOf(HomeVideoCurationException::class.java)
        assertThat(requests).isEmpty()
    }

    private fun judge(): OpenAiCandidateJudge =
        OpenAiCandidateJudge(mapper, "synthetic-key", "gpt-6-luna", http, endpoint()) { delay ->
            pauses.add(delay)
        }

    private fun endpoint(): URI = URI.create("http://127.0.0.1:" + server.address.port + "/v1")

    private fun answer(text: String): String {
        val response = mapOf(
            "choices" to listOf(mapOf("finish_reason" to "stop", "message" to mapOf("content" to wrap(text)))),
            "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 5),
        )
        return mapper.writeValueAsString(response)
    }

    /** 목록 답은 strict 스키마대로 verdicts로 감싼다. 목록이 아닌 글은 그대로 둔다. */
    private fun wrap(text: String): String = if (text.startsWith("[")) "{\"verdicts\":$text}" else text

    private fun video(id: String): CandidateVideo =
        CandidateVideo(
            id,
            "UC-$id",
            "개인 채널",
            "유기견 입양 브이로그",
            "첫 산책",
            600,
            5_000,
            Instant.parse("2026-01-01T00:00:00Z"),
            true,
        )

    private data class Request(val path: String, val key: String?, val body: String)
}
