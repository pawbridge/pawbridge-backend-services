package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import feign.Client
import feign.Request
import feign.Response
import feign.form.FormData
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockMultipartFile
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.charset.StandardCharsets

class PythonLostSearchClientTest {
    private val transport = mockk<Client>()
    private val repository = mockk<AnimalRepository>()
    private val manager = mockk<JpaTransactionManager>()
    private val mapper = ObjectMapper()

    private fun client(): PythonLostSearchClient {
        return PythonLostSearchClient("http://synthetic.invalid", mapper, transport)
    }

    private fun search(): PythonLostSearchResponse? {
        return client().search(
            KEY,
            FormData("application/octet-stream", "photo", "image-bytes".toByteArray(StandardCharsets.UTF_8)),
            "DOG",
            "2026-10-20",
            "서울",
            "귀가 접힌 강아지",
            true,
            true,
        )
    }

    private fun reply(request: Request, status: Int, body: String): Response {
        return Response.builder()
            .request(request)
            .status(status)
            .headers(mapOf("Content-Type" to listOf("application/json")))
            .body(body, StandardCharsets.UTF_8)
            .build()
    }

    @Test
    fun `모든 선택 값이 있을 때__검색하면__멀티파트 파트와 키와 타임아웃을 원래대로 보내고 후보를 읽는다`() {
        // given
        every { transport.execute(any(), any()) } answers {
            reply(
                firstArg(),
                200,
                "{\"candidates\":[{\"animalId\":7,\"imageScore\":0.9,\"matchedEvidence\":[\"FOUND_ON_OR_AFTER_LOST_DATE\"]}]}",
            )
        }

        // when
        val result = search()!!

        // then
        val request = slot<Request>()
        val options = slot<Request.Options>()
        verify(exactly = 1) { transport.execute(capture(request), capture(options)) }
        assertThat(request.captured.httpMethod()).isEqualTo(Request.HttpMethod.POST)
        assertThat(request.captured.url()).isEqualTo("http://synthetic.invalid/internal/animals/lost-candidates")
        assertThat(request.captured.headers()["X-Internal-Api-Key"]).containsExactly(KEY)
        assertThat(request.captured.headers()["Content-Type"]!!.iterator().next())
            .startsWith("multipart/form-data; charset=UTF-8; boundary=")
        val body = String(request.captured.body(), StandardCharsets.UTF_8)
        assertThat(body).contains(
            "name=\"image\"; filename=\"photo\"", "Content-Type: application/octet-stream", "image-bytes",
            "name=\"species\"", "DOG", "name=\"lostDate\"", "2026-10-20", "name=\"region\"", "서울",
            "name=\"description\"", "귀가 접힌 강아지", "name=\"includeAdoptedOrReturned\"", "true",
            "name=\"includeDeceased\"",
        ).doesNotContain(KEY)
        assertThat(options.captured.connectTimeoutMillis()).isEqualTo(3000)
        assertThat(options.captured.readTimeoutMillis()).isEqualTo(45000)
        assertThat(options.captured.isFollowRedirects).isFalse()
        assertThat(result.candidates).hasSize(1)
        assertThat(result.candidates!!.first()!!.animalId).isEqualTo(7L)
    }

    @Test
    fun `선택 값이 없을 때__검색하면__널 선택 파트는 빼고 false는 명시적 파트로 남긴다`() {
        // given
        every { transport.execute(any(), any()) } answers { reply(firstArg(), 200, "{\"candidates\":[]}") }

        // when
        val result = client().search(
            KEY,
            FormData("application/octet-stream", "photo", byteArrayOf(1)),
            "CAT",
            null,
            null,
            null,
            false,
            null,
        )

        // then
        val request = slot<Request>()
        verify(exactly = 1) { transport.execute(capture(request), any()) }
        val body = String(request.captured.body(), StandardCharsets.UTF_8)
        // 사망 기록을 켜지 않으면 이 입력을 모르는 이전 네이티브도 받도록 파트 자체를 보내지 않는다.
        assertThat(body).doesNotContain(
            "name=\"lostDate\"", "name=\"region\"", "name=\"description\"", "name=\"includeDeceased\"", "null",
        )
        assertThat(body).contains("name=\"includeAdoptedOrReturned\"", "false")
        assertThat(result!!.candidates).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 422, 413, 503])
    fun `성공이 아닌 상태 코드일 때__검색하면__서비스가 상태를 분류하고 내부 키를 로그에 남기지 않는다`(status: Int) {
        // given
        every { transport.execute(any(), any()) } answers { reply(firstArg(), status, KEY) }

        // when
        val outcome = searchThroughService()

        // then
        assertSanitizedServiceFailure(
            outcome,
            if (status == 413) {
                413
            } else if (status == 400 || status == 422) {
                400
            } else {
                503
            },
        )
        verify(exactly = 1) { transport.execute(any(), any()) }
    }

    @Test
    fun `깨진 JSON이 돌아올 때__검색하면__503이고 디코더 본문이나 키를 노출하지 않는다`() {
        // given
        every { transport.execute(any(), any()) } answers { reply(firstArg(), 200, KEY) }

        // when
        val outcome = searchThroughService()

        // then
        assertSanitizedServiceFailure(outcome, 503)
        verify(exactly = 1) { transport.execute(any(), any()) }
    }

    @Test
    fun `전송이 IO 오류로 실패할 때__검색하면__재시도 없이 503으로 가려서 돌려준다`() {
        // given
        every { transport.execute(any(), any()) } throws IOException(KEY)

        // when
        val outcome = searchThroughService()

        // then
        assertSanitizedServiceFailure(outcome, 503)
        verify(exactly = 1) { transport.execute(any(), any()) }
    }

    private class Outcome(val failure: Throwable?, val logged: List<String>)

    private fun searchThroughService(): Outcome {
        val log = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val events = ListAppender<ILoggingEvent>()
        events.start()
        log.addAppender(events)
        try {
            val service = LostSearchService(client(), repository, AnimalMapper(), KEY, manager)
            val request = LostSearchRequest()
            request.species = Species.DOG
            request.image = MockMultipartFile("image", byteArrayOf(1))

            val failure = catchThrowable { service.search(request) }

            return Outcome(failure, events.list.map { event -> event.formattedMessage })
        } finally {
            log.detachAppender(events)
            events.stop()
        }
    }

    private fun assertSanitizedServiceFailure(outcome: Outcome, status: Int) {
        assertThat(outcome.failure).isInstanceOfSatisfying(ResponseStatusException::class.java) { error ->
            assertThat(error.statusCode.value()).isEqualTo(status)
            assertThat(error.cause).isNull()
            assertThat(error.toString()).doesNotContain(KEY)
        }
        assertThat(outcome.logged).noneMatch { message -> message.contains(KEY) }
        confirmVerified(repository, manager)
    }

    companion object {
        private const val KEY = "synthetic-encoder-key-never-log"
    }
}
