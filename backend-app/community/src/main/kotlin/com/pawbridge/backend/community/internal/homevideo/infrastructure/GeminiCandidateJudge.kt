package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.homevideo.application.CandidateJudge
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoCurationException
import com.pawbridge.backend.community.internal.homevideo.domain.CandidateVideo
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.slf4j.LoggerFactory

/**
 * Gemini로 후보가 홈 목적에 맞는지 한 번에 판정한다. 키는 주소가 아닌 헤더로 보내고, 오류와 로그에 남기지 않는다.
 * [endpoint]는 모델 목록 주소(`https://generativelanguage.googleapis.com/v1beta/models`)다.
 */
class GeminiCandidateJudge(
    private val mapper: ObjectMapper,
    private val apiKey: String,
    private val model: String,
    private val http: HttpClient,
    private val endpoint: URI,
    private val pause: (Duration) -> Unit = { delay -> Thread.sleep(delay.toMillis()) },
) : CandidateJudge {

    override fun judge(videos: List<CandidateVideo>): Map<String, Boolean> {
        if (apiKey.isBlank()) {
            throw HomeVideoCurationException("판정용 Gemini 키가 설정되지 않았습니다.")
        }
        if (!MODEL.matches(model)) {
            throw HomeVideoCurationException("판정용 Gemini 모델 이름이 올바르지 않습니다.")
        }
        if (videos.isEmpty()) {
            return emptyMap()
        }
        val body = mapper.writeValueAsBytes(request(videos))
        val root = send(body)
        return verdicts(root, videos.map { video -> video.videoId }.toSet())
    }

    /**
     * 일시 오류(5xx, 연결 실패, 시간 초과)만 다시 보낸다. 맛보기에서 503이 두 번 이어진 뒤 성공했다.
     * 429(한도)와 4xx는 다시 보내도 같아 바로 실패한다. 작업이 매일 다시 시도하므로 여기서 오래 버티지 않는다.
     */
    private fun send(body: ByteArray): JsonNode {
        var lastFailure = "연결 실패"
        for (attempt in 1..RETRY_DELAYS.size + 1) {
            if (attempt > 1) {
                pause(RETRY_DELAYS[attempt - 2])
            }
            val response = post(body)
            if (response == null) {
                lastFailure = "연결 실패"
                continue
            }
            if (response.statusCode() == 200) {
                return parse(response.body())
            }
            lastFailure = "HTTP " + response.statusCode()
            log.warn("Home video judge request failed: attempt={} status={}", attempt, response.statusCode())
            if (response.statusCode() < 500) {
                break
            }
        }
        throw HomeVideoCurationException("판정을 받지 못했습니다($lastFailure).")
    }

    /** 연결 실패와 시간 초과는 null. 예외에는 주소나 키가 들어 있을 수 있어 원인을 남기지 않는다. */
    private fun post(body: ByteArray): HttpResponse<ByteArray>? {
        val request = HttpRequest.newBuilder(URI.create("$endpoint/$model:generateContent"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
        try {
            val sent = http.sendAsync(request, HttpResponse.BodyHandler<ByteArray> { BoundedBody(MAX_BODY_BYTES) })
            pending = sent
            return sent.get(REQUEST_TIMEOUT.seconds + 5, TimeUnit.SECONDS)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HomeVideoCurationException("판정을 기다리다 중단되었습니다.")
        } catch (failure: ExecutionException) {
            log.warn("Home video judge request failed: error={}", failure.cause?.javaClass?.simpleName)
            return null
        } catch (failure: TimeoutException) {
            log.warn("Home video judge request timed out")
            return null
        } finally {
            if (pending != null && !pending.isDone) {
                pending.cancel(true)
            }
        }
    }

    private fun parse(body: ByteArray): JsonNode {
        val root: JsonNode
        try {
            root = mapper.readTree(body)
        } catch (failure: IOException) {
            throw HomeVideoCurationException("판정 응답을 읽지 못했습니다.")
        }
        val usage = root.path("usageMetadata")
        log.info(
            "Home video judge response: model={} promptTokens={} outputTokens={}",
            model,
            usage.path("promptTokenCount").asText(),
            usage.path("candidatesTokenCount").asText(),
        )
        return root
    }

    private fun verdicts(root: JsonNode, requested: Set<String>): Map<String, Boolean> {
        val candidate = root.path("candidates").path(0)
        if (candidate.path("finishReason").asText() == "MAX_TOKENS") {
            throw HomeVideoCurationException("판정 응답이 길이 상한에 걸려 잘렸습니다.")
        }
        val text = StringBuilder()
        for (part in candidate.path("content").path("parts")) {
            text.append(part.path("text").asText())
        }
        val answers: JsonNode
        try {
            answers = mapper.readTree(text.toString())
        } catch (failure: IOException) {
            throw HomeVideoCurationException("판정 응답이 JSON이 아닙니다.")
        }
        if (!answers.isArray) {
            throw HomeVideoCurationException("판정 응답이 목록이 아닙니다.")
        }
        // 묻지 않은 ID나 참거짓이 아닌 답은 버린다. 빠진 영상은 다음 검색에서 다시 판정한다.
        return CandidatePrompt.verdicts(answers, requested)
    }

    private fun request(videos: List<CandidateVideo>): Map<String, Any> {
        val schema = mapOf(
            "type" to "ARRAY",
            "items" to mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "id" to mapOf("type" to "STRING"),
                    "fit" to mapOf("type" to "BOOLEAN"),
                    "reason" to mapOf("type" to "STRING"),
                ),
                "required" to listOf("id", "fit", "reason"),
            ),
        )
        return mapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to CandidatePrompt.text(mapper, videos))))),
            "generationConfig" to mapOf(
                "temperature" to 0,
                "maxOutputTokens" to MAX_OUTPUT_TOKENS,
                "responseMimeType" to "application/json",
                "responseSchema" to schema,
            ),
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(GeminiCandidateJudge::class.java)
        private val MODEL = Regex("[a-z0-9][a-z0-9.-]{0,63}")
        private val RETRY_DELAYS = listOf(Duration.ofSeconds(2), Duration.ofSeconds(5))
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(90)
        private const val MAX_BODY_BYTES = 1_048_576L
        private const val MAX_OUTPUT_TOKENS = 8192
    }
}
