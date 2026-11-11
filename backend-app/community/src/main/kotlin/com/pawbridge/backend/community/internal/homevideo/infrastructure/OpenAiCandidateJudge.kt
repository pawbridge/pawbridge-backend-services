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
 * OpenAI(Chat Completions)로 후보가 홈 목적에 맞는지 한 번에 판정한다. 질문은 Gemini와 같다([CandidatePrompt]).
 * 키는 Authorization 헤더로만 보내고, 오류와 로그에 남기지 않는다. [endpoint]는 `https://api.openai.com/v1`이다.
 */
class OpenAiCandidateJudge(
    private val mapper: ObjectMapper,
    private val apiKey: String,
    private val model: String,
    private val http: HttpClient,
    private val endpoint: URI,
    private val pause: (Duration) -> Unit = { delay -> Thread.sleep(delay.toMillis()) },
) : CandidateJudge {

    override fun judge(videos: List<CandidateVideo>): Map<String, Boolean> {
        if (apiKey.isBlank()) {
            throw HomeVideoCurationException("판정용 OpenAI 키가 설정되지 않았습니다.")
        }
        if (!MODEL.matches(model)) {
            throw HomeVideoCurationException("판정용 OpenAI 모델 이름이 올바르지 않습니다.")
        }
        if (videos.isEmpty()) {
            return emptyMap()
        }
        val body = mapper.writeValueAsBytes(request(videos))
        val root = send(body)
        return verdicts(root, videos.map { video -> video.videoId }.toSet())
    }

    /** 일시 오류(5xx, 연결 실패, 시간 초과)만 다시 보낸다. 429(한도)와 4xx는 다시 보내도 같아 바로 실패한다. */
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
        val request = HttpRequest.newBuilder(URI.create("$endpoint/chat/completions"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
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
        val usage = root.path("usage")
        log.info(
            "Home video judge response: model={} promptTokens={} outputTokens={} reasoningTokens={}",
            model,
            usage.path("prompt_tokens").asText(),
            usage.path("completion_tokens").asText(),
            usage.path("completion_tokens_details").path("reasoning_tokens").asText(),
        )
        return root
    }

    private fun verdicts(root: JsonNode, requested: Set<String>): Map<String, Boolean> {
        val choice = root.path("choices").path(0)
        if (choice.path("finish_reason").asText() == "length") {
            throw HomeVideoCurationException("판정 응답이 길이 상한에 걸려 잘렸습니다.")
        }
        val message = choice.path("message")
        if (!message.path("refusal").isMissingNode && !message.path("refusal").isNull) {
            throw HomeVideoCurationException("판정 요청이 거절되었습니다.")
        }
        val answers: JsonNode
        try {
            answers = mapper.readTree(message.path("content").asText()).path("verdicts")
        } catch (failure: IOException) {
            throw HomeVideoCurationException("판정 응답이 JSON이 아닙니다.")
        }
        if (!answers.isArray) {
            throw HomeVideoCurationException("판정 응답이 목록이 아닙니다.")
        }
        return CandidatePrompt.verdicts(answers, requested)
    }

    private fun request(videos: List<CandidateVideo>): Map<String, Any> {
        val verdict = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("id", "fit", "reason"),
            "properties" to mapOf(
                "id" to mapOf("type" to "string"),
                "fit" to mapOf("type" to "boolean"),
                "reason" to mapOf("type" to "string"),
            ),
        )
        // strict 스키마는 맨 바깥이 객체여야 해서 목록을 verdicts로 감싼다.
        val schema = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("verdicts"),
            "properties" to mapOf("verdicts" to mapOf("type" to "array", "items" to verdict)),
        )
        return mapOf(
            "model" to model,
            "messages" to listOf(mapOf("role" to "user", "content" to CandidatePrompt.text(mapper, videos))),
            // 추론 토큰도 이 상한에 들어간다(맛보기 70개: 출력 3,789 중 추론 1,943).
            "max_completion_tokens" to MAX_COMPLETION_TOKENS,
            "response_format" to mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf("name" to "verdicts", "strict" to true, "schema" to schema),
            ),
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OpenAiCandidateJudge::class.java)
        private val MODEL = Regex("[a-z0-9][a-z0-9.-]{0,63}")
        private val RETRY_DELAYS = listOf(Duration.ofSeconds(2), Duration.ofSeconds(5))
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(120)
        private const val MAX_BODY_BYTES = 1_048_576L
        private const val MAX_COMPLETION_TOKENS = 16_384
    }
}
