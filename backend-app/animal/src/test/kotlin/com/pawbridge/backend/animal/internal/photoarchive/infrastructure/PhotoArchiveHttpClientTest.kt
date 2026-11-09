package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import javax.net.ssl.SSLSession

class PhotoArchiveHttpClientTest {
    private val transport: HttpClient = mockk<HttpClient>()
    private val raw = byteArrayOf(1, 2, 3)

    private fun client(): PhotoArchiveHttpClient {
        val p = PhotoArchiveProperties()
        p.optimizerUrl = URI.create("http://optimizer/internal/photos/optimize")
        p.internalApiKey = "test-only-key"
        return PhotoArchiveHttpClient(transport, p)
    }

    private fun headers(bytes: ByteArray): MutableMap<String, List<String>> {
        return hashMapOf(
            "Content-Type" to listOf("image/png"),
            "X-Source-Sha256" to listOf(ArchivedPhoto.sha256(raw)),
            "X-Stored-Sha256" to listOf(ArchivedPhoto.sha256(bytes)),
            "X-Photo-Width" to listOf("1"),
            "X-Photo-Height" to listOf("1"),
            "X-Photo-Recipe" to listOf("original-v1"),
        )
    }

    /** In-memory fake of the three response members the adapter reads; it records nothing and stubs nothing. */
    private class FakeResponse(
        private val status: Int,
        private val bytes: ByteArray,
        headerMap: Map<String, List<String>>,
    ) : HttpResponse<ByteArray> {
        private val responseHeaders = HttpHeaders.of(headerMap) { _, _ -> true }

        override fun statusCode(): Int = status

        override fun body(): ByteArray = bytes

        override fun headers(): HttpHeaders = responseHeaders

        override fun request(): HttpRequest = throw UnsupportedOperationException()

        override fun previousResponse(): Optional<HttpResponse<ByteArray>> = Optional.empty()

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri(): URI = throw UnsupportedOperationException()

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    @Test
    fun `임의 호스트와 사용자 정보와 비표준 포트의 URL일 때__사진을 내려받으면__네트워크 호출 전에 거부한다`() {
        // given
        val urls = listOf(
            "http://127.0.0.1/x",
            "http://openapi.animal.go.kr.evil.test/x",
            "http://user@openapi.animal.go.kr/x",
            "file:///tmp/x",
            "http://openapi.animal.go.kr:8080/x",
            "https://openapi.animal.go.kr/[photo].jpg#fragment",
            "https://[openapi.animal.go.kr]/[photo].jpg",
            "https://openapi.animal.go.kr.evil.test/[photo].jpg",
            "https://user@openapi.animal.go.kr/[photo].jpg",
            "https://openapi.animal.go.kr:8080/[photo].jpg",
        )

        // when
        // then
        for (url in urls) {
            assertThatThrownBy { client().download(url) }.hasMessage("SOURCE_URL")
        }
        confirmVerified(transport)
    }

    @Test
    fun `경로에 대괄호가 있는 원본 URL일 때__사진을 내려받으면__기존 이스케이프와 쿼리는 두고 경로의 대괄호만 인코딩한다`() {
        // given
        val urls = mapOf(
            "https://openapi.animal.go.kr/photos/[notice]%20dog.jpg?name=[dog]&q=%5Bok%5D" to
                "https://openapi.animal.go.kr/photos/%5Bnotice%5D%20dog.jpg?name=[dog]&q=%5Bok%5D",
            "http://openapi.animal.go.kr/a%5Bb%5D[1].jpg" to
                "http://openapi.animal.go.kr/a%5Bb%5D%5B1%5D.jpg",
        )

        for (entry in urls.entries) {
            clearMocks(transport)
            every {
                transport.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
            } returns CompletableFuture.completedFuture(FakeResponse(200, raw, mapOf()))

            // when
            val downloaded = client().download(entry.key)

            // then
            val request = slot<HttpRequest>()
            assertThat(downloaded).isEqualTo(raw)
            verify(exactly = 1) { transport.sendAsync(capture(request), any<HttpResponse.BodyHandler<ByteArray>>()) }
            assertThat(request.captured.uri().toString()).isEqualTo(entry.value)
            assertThat(request.captured.headers().firstValue("X-Internal-API-Key")).isEmpty()
        }
    }

    @Test
    fun `원본이 리다이렉트로 응답할 때__사진을 내려받으면__사진으로 취급하지 않고 내부 키도 보내지 않는다`() {
        // given
        every {
            transport.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        } returns CompletableFuture.completedFuture(
            FakeResponse(302, ByteArray(0), mapOf("Location" to listOf("http://127.0.0.1/private"))),
        )

        // when
        val thrown = assertThatThrownBy { client().download("https://openapi.animal.go.kr/photo") }

        // then
        val request = slot<HttpRequest>()
        thrown.hasMessage("SOURCE_HTTP_302")
        verify(exactly = 1) { transport.sendAsync(capture(request), any<HttpResponse.BodyHandler<ByteArray>>()) }
        assertThat(request.captured.headers().firstValue("X-Internal-API-Key")).isEmpty()
    }

    @Test
    fun `최적화 서버가 계약에 맞게 응답할 때__사진을 최적화하면__원본을 보존하고 내부 키를 보낸다`() {
        // given
        every {
            transport.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        } returns CompletableFuture.completedFuture(FakeResponse(200, raw, headers(raw)))

        // when
        val photo = client().optimize(raw)

        // then
        val request = slot<HttpRequest>()
        assertThat(photo.bytes).isEqualTo(raw)
        assertThat(photo.objectKey()).isEqualTo("apms/photos/" + ArchivedPhoto.sha256(raw) + ".png")
        verify(exactly = 1) { transport.sendAsync(capture(request), any<HttpResponse.BodyHandler<ByteArray>>()) }
        assertThat(request.captured.headers().firstValue("X-Internal-API-Key")).contains("test-only-key")
    }

    @Test
    fun `해시가 틀리거나 헤더가 중복되거나 크기가 지나치게 클 때__사진을 최적화하면__계약 위반으로 실패한다`() {
        // given
        val changes = mapOf(
            "X-Source-Sha256" to listOf("wrong"),
            "X-Stored-Sha256" to listOf("wrong"),
            "X-Photo-Width" to listOf("99999999"),
            "Content-Type" to listOf("image/png", "image/jpeg"),
        )

        for (change in changes.entries) {
            val h = headers(raw)
            h[change.key] = change.value

            every {
                transport.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
            } returns CompletableFuture.completedFuture(FakeResponse(200, raw, h))

            // when
            val thrown = assertThatThrownBy { client().optimize(raw) }

            // then
            thrown.hasMessage("OPTIMIZER_CONTRACT")
        }
    }

    @Test
    fun `원본 레시피일 때__해시가 유효해도 바이트가 바뀌어 돌아오면__최적화는 계약 위반으로 실패한다`() {
        // given
        val changed = byteArrayOf(3, 2, 1)

        every {
            transport.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        } returns CompletableFuture.completedFuture(FakeResponse(200, changed, headers(changed)))

        // when
        val thrown = assertThatThrownBy { client().optimize(raw) }

        // then
        thrown.hasMessage("OPTIMIZER_CONTRACT")
    }

    @Test
    fun `최적화 서버를 쓸 수 없을 때__사진을 최적화하면__조용히 대체하지 않고 재시도 가능한 실패가 된다`() {
        // given
        every {
            transport.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        } returns CompletableFuture.completedFuture(FakeResponse(503, ByteArray(0), mapOf()))

        // when
        val thrown = assertThatThrownBy { client().optimize(raw) }

        // then
        thrown.isInstanceOfSatisfying(PhotoArchiveFailure::class.java) { failure ->
            assertThat(failure.slowRetry()).isFalse()
        }
    }

    @Test
    fun `Content-Length 없이 스트리밍될 때__본문이 한도를 넘으면__구독을 취소한다`() {
        // given
        val body = PhotoArchiveHttpClient.LimitedBody()
        val subscription = mockk<Flow.Subscription>()

        justRun { subscription.request(any()) }
        justRun { subscription.cancel() }

        // when
        body.onSubscribe(subscription)
        body.onNext(listOf(ByteBuffer.wrap(ByteArray(ArchivedPhoto.MAX_BYTES)), ByteBuffer.wrap(ByteArray(1))))

        // then
        verify(exactly = 1) { subscription.cancel() }
        assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally()
    }

    @Test
    fun `주입받은 빌린 클라이언트일 때__어댑터를 두 번 닫으면__전송 소유권은 호출자에게 남긴다`() {
        // given
        val borrowed = client()

        // when
        borrowed.close()
        borrowed.close()

        // then
        confirmVerified(transport)
    }

    @Test
    fun `공개 생성자로 만든 소유 클라이언트일 때__어댑터를 두 번 닫으면__만든 Java 21 전송을 정확히 한 번 닫는다`() {
        // given
        val builder = mockk<HttpClient.Builder>()
        val settings = PhotoArchiveProperties()
        settings.optimizerUrl = URI.create("http://optimizer/internal/photos/optimize")
        settings.internalApiKey = "test-only-key"

        every { builder.version(HttpClient.Version.HTTP_1_1) } returns builder
        every { builder.connectTimeout(any()) } returns builder
        every { builder.followRedirects(HttpClient.Redirect.NEVER) } returns builder
        every { builder.build() } returns transport
        justRun { transport.close() }

        // when
        mockkStatic(HttpClient::class)
        try {
            every { HttpClient.newBuilder() } returns builder
            val owned = PhotoArchiveHttpClient(settings)
            owned.close()
            owned.close()
        } finally {
            unmockkStatic(HttpClient::class)
        }

        // then
        verify(exactly = 1) { transport.close() }
        confirmVerified(transport)
    }

    @Test
    fun `공개 생성자로 만들 때__전송을 만들면__평문 최적화 서버에 h2c 업그레이드를 보내지 않도록 HTTP 1_1로 고정한다`() {
        // given
        val builder = mockk<HttpClient.Builder>()
        val settings = PhotoArchiveProperties()
        settings.optimizerUrl = URI.create("http://optimizer/internal/photos/optimize")
        settings.internalApiKey = "test-only-key"

        every { builder.version(any()) } returns builder
        every { builder.connectTimeout(any()) } returns builder
        every { builder.followRedirects(any()) } returns builder
        every { builder.build() } returns transport

        // when
        mockkStatic(HttpClient::class)
        try {
            every { HttpClient.newBuilder() } returns builder
            PhotoArchiveHttpClient(settings)
        } finally {
            unmockkStatic(HttpClient::class)
        }

        // then
        verify(exactly = 1) { builder.version(HttpClient.Version.HTTP_1_1) }
    }
}
