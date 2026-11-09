package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsRootResponse
import com.pawbridge.backend.boot.javaIsBlank
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * APMS transport only. Snapshot retains its existing header checks and bounded recovery.
 * The primary constructor is the explicit ownership seam for offline close regression tests.
 */
internal class ApmsHttpClient(
    base: URI?,
    private val mapper: ObjectMapper,
    private val client: HttpClient,
    private val ownsClient: Boolean,
) : ApmsApiClient, AutoCloseable {
    private val base: URI = baseUri(base?.toString())
    private val responseType: JavaType =
        mapper.typeFactory.constructParametricType(ApmsRootResponse::class.java, ApmsAnimal::class.java)

    @Volatile
    private var closed = false

    constructor(base: URI?, mapper: ObjectMapper) : this(
        base,
        mapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        true,
    )

    /** Borrowed test seam: closing this adapter never closes the caller's HttpClient. */
    constructor(base: URI?, mapper: ObjectMapper, client: HttpClient) : this(base, mapper, client, false)

    override fun getAbandonmentAnimals(
        serviceKey: String?,
        pageNo: Int,
        numOfRows: Int,
        bgnde: String?,
        endde: String?,
        upkind: String?,
        state: String?,
        type: String?,
        updatedBeginDate: String?,
        updatedEndDate: String?,
    ): ApmsRootResponse<ApmsAnimal>? {
        val started = System.nanoTime()
        var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
        var status: Int? = null
        try {
            if (closed || serviceKey == null || javaIsBlank(serviceKey) ||
                serviceKey.any { Character.isISOControl(it) } ||
                pageNo < 1 || numOfRows < 1 || numOfRows > 1000
            ) {
                throw Unavailable(INVALID_REQUEST)
            }
            val query = StringBuilder()
            parameter(query, "serviceKey", serviceKey)
            parameter(query, "pageNo", Integer.toString(pageNo))
            parameter(query, "numOfRows", Integer.toString(numOfRows))
            parameter(query, "bgnde", bgnde)
            parameter(query, "endde", endde)
            parameter(query, "upkind", upkind)
            parameter(query, "state", state)
            parameter(query, "_type", type ?: "json")
            parameter(query, "bgupd", updatedBeginDate)
            parameter(query, "enupd", updatedEndDate)
            var prefix = base.toASCIIString()
            while (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length - 1)
            }
            val target = URI.create("$prefix/abandonmentPublic_v2?$query")
            val request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build()
            pending = client.sendAsync(request, HttpResponse.BodyHandler<ByteArray> { LimitedBodySubscriber() })
            val response = pending.get(6, TimeUnit.SECONDS)
            status = response.statusCode()
            if (response.statusCode() != 200) {
                throw Unavailable(HTTP_STATUS)
            }
            if (response.body() == null || response.body().size > MAX_BODY_BYTES) {
                throw Unavailable(INVALID_BODY)
            }
            return mapper.readValue<ApmsRootResponse<ApmsAnimal>>(response.body(), responseType)
        } catch (interrupted: InterruptedException) {
            pending?.cancel(true)
            Thread.currentThread().interrupt()
            throw failed(INTERRUPTED, interrupted, status, started, pageNo, numOfRows, bgnde, endde, updatedBeginDate, updatedEndDate)
        } catch (unavailable: Exception) {
            if (pending != null && !pending.isDone) {
                pending.cancel(true)
            }
            throw failed(kindOf(unavailable), unavailable, status, started, pageNo, numOfRows, bgnde, endde, updatedBeginDate, updatedEndDate)
        }
    }

    /**
     * 실패 원인을 키 없는 값으로만 한 줄 남기고 원래 예외는 버린다.
     * 원래 예외의 메시지에는 서비스 키가 든 요청 URL이 들어갈 수 있어 클래스 이름만 남긴다.
     */
    private fun failed(
        kind: String,
        failure: Exception,
        status: Int?,
        started: Long,
        pageNo: Int,
        numOfRows: Int,
        bgnde: String?,
        endde: String?,
        updatedBeginDate: String?,
        updatedEndDate: String?,
    ): Unavailable {
        log.warn(
            "[BATCH] APMS request failed: kind={}, status={}, cause={}, page={}, rows={}, intake={}..{}, updated={}..{}, elapsedMs={}",
            kind,
            status,
            rootOf(failure).javaClass.simpleName,
            pageNo,
            numOfRows,
            bgnde,
            endde,
            updatedBeginDate,
            updatedEndDate,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        )
        return Unavailable(kind)
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        if (ownsClient) {
            try {
                client.close()
            } catch (failure: RuntimeException) {
                throw Unavailable()
            }
        }
    }

    /** [kind]는 로그와 시험에서 실패를 나누는 이름이다. 메시지와 원인에는 아무것도 담지 않는다. */
    class Unavailable(val kind: String = UNAVAILABLE) : IllegalStateException("APMS request unavailable", null)

    /** Cancel before copying a buffer that would exceed the body limit. */
    class LimitedBodySubscriber : HttpResponse.BodySubscriber<ByteArray> {
        private val bytes = ByteArrayOutputStream()
        private val result = CompletableFuture<ByteArray>()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> {
            return result
        }

        override fun onSubscribe(incoming: Flow.Subscription) {
            if (subscription != null) {
                incoming.cancel()
                return
            }
            subscription = incoming
            incoming.request(1)
        }

        override fun onNext(buffers: MutableList<ByteBuffer>) {
            if (result.isDone) {
                return
            }
            for (buffer in buffers) {
                if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
                    subscription!!.cancel()
                    result.completeExceptionally(Unavailable(BODY_TOO_LARGE))
                    return
                }
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                bytes.writeBytes(chunk)
            }
            subscription!!.request(1)
        }

        override fun onError(ignored: Throwable) {
            result.completeExceptionally(Unavailable(BODY_READ))
        }

        override fun onComplete() {
            result.complete(bytes.toByteArray())
        }
    }

    companion object {
        const val MAX_BODY_BYTES: Int = 2 * 1024 * 1024

        // 실패 종류. 요청 제한 5초(HttpRequest.timeout)는 응답 머리가 올 때까지만 재고,
        // 6초 대기(pending.get)는 본문을 다 받을 때까지 잰다. 둘을 나눠야 어느 쪽이 느린지 알 수 있다.
        const val UNAVAILABLE = "UNAVAILABLE"
        const val INVALID_REQUEST = "INVALID_REQUEST"
        const val HTTP_STATUS = "HTTP_STATUS"
        const val INVALID_BODY = "INVALID_BODY"
        const val BODY_TOO_LARGE = "BODY_TOO_LARGE"
        const val BODY_READ = "BODY_READ"
        const val INVALID_JSON = "INVALID_JSON"
        const val REQUEST_TIMEOUT = "REQUEST_TIMEOUT"
        const val CONNECT_TIMEOUT = "CONNECT_TIMEOUT"
        const val RESPONSE_WAIT_TIMEOUT = "RESPONSE_WAIT_TIMEOUT"
        const val CONNECT = "CONNECT"
        const val IO = "IO"
        const val INTERRUPTED = "INTERRUPTED"
        const val OTHER = "OTHER"

        private val log = LoggerFactory.getLogger(ApmsHttpClient::class.java)

        private fun rootOf(failure: Throwable): Throwable {
            return if ((failure is ExecutionException || failure is CompletionException) && failure.cause != null) {
                failure.cause!!
            } else {
                failure
            }
        }

        private fun kindOf(failure: Exception): String {
            return when (val cause = rootOf(failure)) {
                is Unavailable -> cause.kind
                is TimeoutException -> RESPONSE_WAIT_TIMEOUT
                is HttpConnectTimeoutException -> CONNECT_TIMEOUT
                is HttpTimeoutException -> REQUEST_TIMEOUT
                is ConnectException -> CONNECT
                // JsonProcessingException도 IOException이므로 먼저 본다.
                is JsonProcessingException -> INVALID_JSON
                is IOException -> IO
                else -> OTHER
            }
        }

        @JvmStatic
        fun baseUri(configured: String?): URI {
            try {
                if (configured == null || javaIsBlank(configured)) {
                    throw IllegalArgumentException()
                }
                val uri = URI.create(configured)
                if ("https" != uri.scheme || uri.host == null || javaIsBlank(uri.host) ||
                    uri.rawAuthority == null || uri.rawUserInfo != null ||
                    uri.rawQuery != null || uri.rawFragment != null ||
                    uri.port == 0 || uri.port > 65535
                ) {
                    throw IllegalArgumentException()
                }
                return uri
            } catch (invalid: RuntimeException) {
                throw IllegalArgumentException("APMS base URL must be an explicit HTTPS provider URL")
            }
        }

        private fun parameter(query: StringBuilder, key: String, value: String?) {
            if (value == null) {
                return
            }
            if (query.isNotEmpty()) {
                query.append('&')
            }
            query.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8))
        }
    }
}
