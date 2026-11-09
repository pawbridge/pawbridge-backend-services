package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PhotoArchiveHttpClient private constructor(
    private val http: HttpClient,
    properties: PhotoArchiveProperties,
    private val ownsClient: Boolean,
) : AutoCloseable {
    private val optimizer: URI? = properties.optimizerUrl
    private val key: String? = properties.internalApiKey
    private val allowedHosts: Set<String> = java.util.Set.copyOf(properties.allowedHosts)
    private val closed = AtomicBoolean()

    constructor(properties: PhotoArchiveProperties) : this(
        HttpClient.newBuilder()
            // 기본값 HTTP/2는 평문 http 최적화 서버 호출마다 h2c 업그레이드 헤더를 붙여 uvicorn 경고를 남긴다.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        properties,
        true,
    )

    // Package-private in Java; the same-package tests inject a borrowed transport.
    internal constructor(http: HttpClient, properties: PhotoArchiveProperties) : this(http, properties, false)

    /** Java21 client lifetime is owned only by the public properties constructor. */
    override fun close() {
        if (ownsClient && closed.compareAndSet(false, true)) {
            http.close()
        }
    }

    // The URL is nullable like the Java String: a null URL fails inside the try block as SOURCE_URL.
    fun download(url: String?): ByteArray {
        val uri: URI
        try {
            // APMS filenames can contain literal brackets; preserve authority, query and existing escapes.
            val path = SOURCE_PATH.matcher(url)
            val encoded = if (path.find()) {
                path.group(1) + path.group(2).replace("[", "%5B").replace("]", "%5D") + url!!.substring(path.end())
            } else {
                url
            }
            uri = URI.create(encoded)
        } catch (malformed: RuntimeException) {
            throw PhotoArchiveFailure("SOURCE_URL", true)
        }
        if (uri.host == null || uri.userInfo != null || uri.fragment != null ||
            !WEB_SCHEMES.contains(uri.scheme) ||
            !allowedHosts.contains(uri.host.lowercase(Locale.ROOT)) ||
            (uri.port != -1 && uri.port != (if ("https" == uri.scheme) 443 else 80))
        ) {
            throw PhotoArchiveFailure("SOURCE_URL", true)
        }
        val response = send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), 30)
        checkStatus(response.statusCode(), "SOURCE")
        if (response.body().isEmpty()) {
            throw PhotoArchiveFailure("SOURCE_EMPTY", true)
        }
        return response.body()
    }

    fun optimize(source: ByteArray): ArchivedPhoto {
        if (source.isEmpty() || source.size > ArchivedPhoto.MAX_BYTES) {
            throw PhotoArchiveFailure("SOURCE_SIZE", true)
        }
        val request = HttpRequest.newBuilder(optimizer)
            .timeout(Duration.ofSeconds(60))
            .header("X-Internal-API-Key", key)
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(source))
            .build()
        val response = send(request, 60)
        checkStatus(response.statusCode(), "OPTIMIZER")
        try {
            val stored = response.body()
            val mime = header(response, "Content-Type")
            val sourceHash = header(response, "X-Source-Sha256")
            val storedHash = header(response, "X-Stored-Sha256")
            val recipe = header(response, "X-Photo-Recipe")
            val width = Integer.parseInt(header(response, "X-Photo-Width"))
            val height = Integer.parseInt(header(response, "X-Photo-Height"))
            val original = "original-v1" == recipe
            if (stored.isEmpty() || stored.size > source.size ||
                !IMAGE_TYPES.contains(mime) ||
                sourceHash != ArchivedPhoto.sha256(source) ||
                storedHash != ArchivedPhoto.sha256(stored) ||
                width <= 0 || height <= 0 || width.toLong() * height > 16_000_000 ||
                (original && !source.contentEquals(stored)) ||
                (!original && !("webp-q90-m4-fullsize-v1" == recipe && "image/webp" == mime && stored.size < source.size))
            ) {
                throw IllegalArgumentException()
            }
            return ArchivedPhoto(stored, sourceHash, storedHash, mime, width, height, recipe)
        } catch (invalid: RuntimeException) {
            throw PhotoArchiveFailure("OPTIMIZER_CONTRACT", true)
        }
    }

    private fun header(response: HttpResponse<*>, name: String): String {
        val values = response.headers().allValues(name)
        if (values.size != 1) {
            throw IllegalArgumentException()
        }
        return values[0]
    }

    private fun checkStatus(status: Int, source: String) {
        if (status != 200) {
            throw PhotoArchiveFailure(
                source + "_HTTP_" + status,
                status >= 300 && status < 500 && status != 408 && status != 429,
            )
        }
    }

    private fun send(request: HttpRequest, seconds: Int): HttpResponse<ByteArray> {
        val future = http.sendAsync(request, HttpResponse.BodyHandler { LimitedBody() })
        try {
            val response = future.get(seconds.toLong(), TimeUnit.SECONDS)
            if (!response.headers().firstValue("Content-Encoding").orElse("identity").equals("identity", ignoreCase = true)) {
                throw PhotoArchiveFailure("HTTP_ENCODING", true)
            }
            return response
        } catch (known: PhotoArchiveFailure) {
            throw known
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PhotoArchiveFailure("HTTP_INTERRUPTED", false)
        } catch (failure: Exception) {
            val cause = failure.cause
            if (cause is PhotoArchiveFailure) {
                throw cause
            }
            throw PhotoArchiveFailure("HTTP_TRANSFER", false)
        } finally {
            if (!future.isDone) {
                future.cancel(true)
            }
        }
    }

    /** Cancels during streaming; a Content-Length header is not trusted as a size limit. */
    internal class LimitedBody : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val output = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            subscription.request(1)
        }

        override fun onNext(items: List<ByteBuffer>) {
            for (item in items) {
                if (output.size().toLong() + item.remaining() > ArchivedPhoto.MAX_BYTES) {
                    subscription!!.cancel()
                    result.completeExceptionally(PhotoArchiveFailure("HTTP_SIZE", true))
                    return
                }
                val chunk = ByteArray(item.remaining())
                item.get(chunk)
                output.writeBytes(chunk)
            }
            subscription!!.request(1)
        }

        override fun onError(failure: Throwable) {
            result.completeExceptionally(failure)
        }

        override fun onComplete() {
            result.complete(output.toByteArray())
        }
    }

    companion object {
        private val SOURCE_PATH = java.util.regex.Pattern.compile("^(https?://[^/?#]+)(/[^?#]*)")

        // Set.of(...).contains(null) throws NullPointerException for a URI without a scheme; keep the Java sets.
        private val WEB_SCHEMES: Set<String> = java.util.Set.of("http", "https")
        private val IMAGE_TYPES: Set<String> = java.util.Set.of("image/jpeg", "image/png", "image/webp")
    }
}
