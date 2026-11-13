package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import feign.FeignException
import feign.Request
import feign.Response
import feign.form.FormData
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockMultipartFile
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

class LostSearchServiceTest {
    private val client = mockk<PythonLostSearchClient>()
    private val animals = mockk<AnimalRepository>()
    private val reader = mockk<JpaTransactionManager>()
    private val transaction = mockk<TransactionStatus>()
    private val mapper = AnimalMapper()
    private val service = LostSearchService(client, animals, mapper, KEY, reader)

    private fun request(): LostSearchRequest {
        val request = LostSearchRequest()
        request.species = Species.DOG
        request.image = MockMultipartFile(
            "image",
            "untrusted-name.png",
            "image/png",
            "image-bytes".toByteArray(StandardCharsets.UTF_8),
        )
        return request
    }

    private fun candidate(id: Long): PythonLostSearchResponse.Candidate {
        return PythonLostSearchResponse.Candidate(id, 0.85, listOf(EVIDENCE))
    }

    private fun animal(id: Long, species: Species, status: AnimalStatus): Animal {
        val shelter = Shelter.builder().id(70L).name("보호소").careRegNo("care-70").phone("02-0000-0000").build()
        return Animal.builder().id(id).species(species).status(status).shelter(shelter).description("현재 설명").build()
    }

    private fun assertStatus(failure: Throwable?, status: Int) {
        assertThat(failure).isInstanceOfSatisfying(ResponseStatusException::class.java) { error ->
            assertThat(error.statusCode.value()).isEqualTo(status)
            assertThat(error.cause).isNull()
            assertThat(error.toString()).doesNotContain(KEY)
        }
    }

    @Test
    fun `이미지와 선택 조건이 있을 때__검색하면__원격 호출이 읽기 트랜잭션보다 먼저이고 순위·중복 제거·첫 근거·현재 데이터를 보존한다`() {
        // given
        val request = request()
        request.region = "  서울  "
        request.description = "  귀가 접힘  "
        request.lostDate = LocalDate.of(2026, 10, 20)

        every { reader.getTransaction(any()) } answers {
            assertThat(firstArg<TransactionDefinition>().isReadOnly).isTrue()
            transaction
        }
        justRun { reader.commit(any()) }
        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returns PythonLostSearchResponse(
            listOf(
                candidate(2),
                candidate(1),
                PythonLostSearchResponse.Candidate(2L, .9, listOf("DISCOVERY_PLACE_TEXT_MATCH")),
                candidate(3),
                candidate(4),
                candidate(5),
            ),
        )
        every { animals.findWithShelterByIdIn(any()) } returns listOf(
            animal(1, Species.DOG, AnimalStatus.PROTECT),
            animal(2, Species.DOG, AnimalStatus.NOTICE),
            animal(3, Species.CAT, AnimalStatus.NOTICE),
            animal(4, Species.DOG, AnimalStatus.ADOPTED),
        )

        // when
        val result = service.search(request)

        // then
        val image = slot<FormData>()
        verifyOrder {
            client.search(KEY, capture(image), "DOG", "2026-10-20", "서울", "귀가 접힘", false, null)
            reader.getTransaction(any())
            animals.findWithShelterByIdIn(listOf(2L, 1L, 3L, 4L, 5L))
            reader.commit(transaction)
        }
        assertThat(image.captured.fileName).isEqualTo("photo")
        assertThat(image.captured.contentType).isEqualTo("application/octet-stream")
        assertThat(image.captured.data).isEqualTo("image-bytes".toByteArray(StandardCharsets.UTF_8))
        assertThat(result.candidates.map { c -> c.animal.id }).containsExactly(2L, 1L)
        assertThat(result.candidates.first().animal.description).isEqualTo("현재 설명")
        assertThat(result.candidates.first().shelterPhone).isEqualTo("02-0000-0000")
        assertThat(result.candidates.first().matchedEvidence).containsExactly(EVIDENCE)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `활성·해결 상태 후보가 섞여 있을 때__현재 상태 필터로 검색하면__해결 포함 여부에 따라 활성 집합과 해결 집합만 남긴다`(includeResolved: Boolean) {
        // given
        val request = request()
        request.includeAdoptedOrReturned = includeResolved
        val statuses = AnimalStatus.values().toList()

        every { reader.getTransaction(any()) } returns transaction
        justRun { reader.commit(any()) }
        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returns PythonLostSearchResponse(
            (0 until statuses.size).map { i -> candidate((i + 1).toLong()) },
        )
        every { animals.findWithShelterByIdIn(any()) } returns
            (0 until statuses.size).map { i -> animal((i + 1).toLong(), Species.DOG, statuses[i]) }

        // when
        val result = service.search(request)

        // then
        assertThat(result.candidates.map { c -> c.animal.status }).containsExactlyElementsOf(
            if (includeResolved) {
                listOf(
                    AnimalStatus.NOTICE,
                    AnimalStatus.PROTECT,
                    AnimalStatus.ADOPTED,
                    AnimalStatus.RETURNED,
                    AnimalStatus.DONATED,
                    AnimalStatus.RELEASED,
                )
            } else {
                listOf(AnimalStatus.NOTICE, AnimalStatus.PROTECT)
            },
        )
    }

    @Test
    fun `사망 기록을 켰을 때__검색하면__켠 파트를 보내고 한 번의 조회로 두 목록을 각각 지금 상태로 거른다`() {
        // given
        val request = request()
        request.includeDeceased = true

        every { reader.getTransaction(any()) } returns transaction
        justRun { reader.commit(any()) }
        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returns PythonLostSearchResponse(
            listOf(candidate(1), candidate(2)),
            listOf(candidate(11), candidate(12), candidate(13), candidate(14)),
        )
        every { animals.findWithShelterByIdIn(any()) } returns listOf(
            animal(1, Species.DOG, AnimalStatus.PROTECT),
            animal(2, Species.DOG, AnimalStatus.EUTHANIZED),
            animal(11, Species.DOG, AnimalStatus.NATURAL_DEATH),
            animal(12, Species.DOG, AnimalStatus.PROTECT),
            animal(13, Species.CAT, AnimalStatus.EUTHANIZED),
            animal(14, Species.DOG, AnimalStatus.EUTHANIZED),
        )

        // when
        val result = service.search(request)

        // then
        verify(exactly = 1) { client.search(KEY, any<FormData>(), "DOG", null, null, null, false, true) }
        verify(exactly = 1) { animals.findWithShelterByIdIn(listOf(1L, 2L, 11L, 12L, 13L, 14L)) }
        // 그새 사망한 2는 살아 있는 목록에서, 그새 보호 상태로 바뀐 12와 다른 축종 13은 사망 목록에서 빠진다.
        assertThat(result.candidates.map { c -> c.animal.id }).containsExactly(1L)
        assertThat(result.deceasedCandidates.map { c -> c.animal.id }).containsExactly(11L, 14L)
        assertThat(result.deceasedCandidates.first().shelterPhone).isEqualTo("02-0000-0000")
    }

    @Test
    fun `사망 기록을 켜지 않았을 때__응답에 사망 기록이 딸려 오면__보내지 않고 쓰지도 않는다`() {
        // given
        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returns
            PythonLostSearchResponse(listOf(), listOf(candidate(11)))

        // when
        val result = service.search(request())

        // then
        verify(exactly = 1) { client.search(KEY, any<FormData>(), "DOG", null, null, null, false, null) }
        assertThat(result.candidates).isEmpty()
        assertThat(result.deceasedCandidates).isEmpty()
        confirmVerified(reader, animals)
    }

    @ParameterizedTest
    @MethodSource("invalidDeceased")
    fun `사망 기록을 켰는데 그 목록이 올바르지 않을 때__검색하면__DB 접근 없이 503이다`(deceased: List<PythonLostSearchResponse.Candidate?>?) {
        // given
        val request = request()
        request.includeDeceased = true
        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returns
            PythonLostSearchResponse(listOf(candidate(1)), deceased)

        // when
        val failure = catchThrowable { service.search(request) }

        // then
        assertStatus(failure, 503)
        confirmVerified(reader, animals)
    }

    @Test
    fun `후보가 비어 있을 때__검색하면__읽기 트랜잭션을 열거나 DB를 조회하지 않는다`() {
        // given
        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returns PythonLostSearchResponse(listOf())

        // when
        val result = service.search(request())

        // then
        assertThat(result.candidates).isEmpty()
        confirmVerified(reader, animals)
    }

    @Test
    fun `후보가 정확히 스무 개일 때__검색하면__중복 제거와 현재 조회 전에 받아들이고 중복을 합친다`() {
        // given
        every { reader.getTransaction(any()) } returns transaction
        justRun { reader.commit(any()) }
        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returns PythonLostSearchResponse(Collections.nCopies(20, candidate(1)))
        every { animals.findWithShelterByIdIn(any()) } returns listOf(animal(1, Species.DOG, AnimalStatus.PROTECT))

        // when
        val result = service.search(request())

        // then
        assertThat(result.candidates).hasSize(1)
        verify(exactly = 1) { animals.findWithShelterByIdIn(listOf(1L)) }
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    fun `파이썬 응답이 올바르지 않을 때__검색하면__DB 접근 없이 503이고 허가를 반납한다`(response: PythonLostSearchResponse?) {
        // given
        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(response, PythonLostSearchResponse(listOf()))

        // when
        val failure = catchThrowable { service.search(request()) }
        val next = service.search(request())

        // then
        assertStatus(failure, 503)
        assertThat(next.candidates).isEmpty()
        confirmVerified(reader, animals)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 422, 413, 429, 503])
    fun `원격 오류 상태가 주어졌을 때__검색하면__민감한 예외를 버리고 413·400·503으로 분류하며 허가를 반납한다`(remoteStatus: Int) {
        // given
        val request = Request.create(
            Request.HttpMethod.POST,
            "http://synthetic.invalid/internal/animals/lost-candidates",
            mapOf("X-Internal-Api-Key" to listOf(KEY)),
            ByteArray(0),
            StandardCharsets.UTF_8,
            null,
        )
        val error = FeignException.errorStatus(
            "search",
            Response.builder().request(request).status(remoteStatus).reason(KEY).body(KEY, StandardCharsets.UTF_8).build(),
        )

        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } throws error andThen PythonLostSearchResponse(listOf())

        // when
        val failure = catchThrowable { service.search(request()) }
        val next = service.search(request())

        // then
        assertStatus(
            failure,
            if (remoteStatus == 413) {
                413
            } else if (remoteStatus == 400 || remoteStatus == 422) {
                400
            } else {
                503
            },
        )
        assertThat(next.candidates).isEmpty()
        confirmVerified(reader, animals)
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong-species", "missing-image", "empty-image", "oversized-image"])
    fun `종이 틀리거나 이미지가 없거나 비었거나 너무 클 때__검색하면__getBytes·클라이언트·DB 전에 거부한다`(kind: String) {
        // given
        val request = request()
        var expected = 400
        var oversized: MultipartFile? = null
        when (kind) {
            "wrong-species" -> request.species = Species.ETC
            "missing-image" -> request.image = null
            "empty-image" -> request.image = MockMultipartFile("image", ByteArray(0))
            "oversized-image" -> {
                val image = mockk<MultipartFile>()
                every { image.isEmpty } returns false
                every { image.size } returns LostSearchService.MAX_IMAGE_BYTES + 1
                request.image = image
                oversized = image
                expected = 413
            }
        }

        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returns PythonLostSearchResponse(listOf())

        // when
        val failure = catchThrowable { service.search(request) }

        // then
        assertStatus(failure, expected)
        confirmVerified(client, reader, animals)
        if (oversized != null) {
            verify(exactly = 0) { oversized.bytes }
        }

        // when
        val next = service.search(request())

        // then
        assertThat(next.candidates).isEmpty()
    }

    @Test
    fun `정확히 5MiB 파일이고 선택 항목이 없을 때__검색하면__파일을 받아들이고 선택 항목은 비운 채 원격에 보낸다`() {
        // given
        val request = request()
        request.image = MockMultipartFile("image", ByteArray(LostSearchService.MAX_IMAGE_BYTES.toInt()))

        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returns PythonLostSearchResponse(listOf())

        // when
        val result = service.search(request)

        // then
        assertThat(result.candidates).isEmpty()
        verify(exactly = 1) { client.search(KEY, any<FormData>(), "DOG", null, null, null, false, null) }
    }

    @Test
    fun `내부 키가 비어 있을 때__검색하면__파일 바이트 읽기나 외부 호출 전에 503으로 실패한다`() {
        // given
        val disabled = LostSearchService(client, animals, mapper, " ", reader)

        // when
        val failure = catchThrowable { disabled.search(request()) }

        // then
        assertStatus(failure, 503)
        confirmVerified(client, reader, animals)
    }

    @Test
    fun `이미지 읽기가 실패할 때__검색하면__400이고 허가를 반납한다`() {
        // given
        val image = mockk<MultipartFile>()
        val request = request()
        request.image = image

        every { image.isEmpty } returns false
        every { image.size } returns 1L
        every { image.bytes } throws IOException(KEY)
        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returns PythonLostSearchResponse(listOf())

        // when
        val failure = catchThrowable { service.search(request) }
        val next = service.search(request())

        // then
        assertStatus(failure, 400)
        assertThat(next.candidates).isEmpty()
        confirmVerified(reader, animals)
    }

    @Test
    fun `DB 조회가 실패할 때__검색하면__읽기 트랜잭션을 롤백하고 실패를 전파하며 허가를 반납한다`() {
        // given
        val failure = DataAccessResourceFailureException("synthetic database failure")

        every { reader.getTransaction(any()) } returns transaction
        justRun { reader.rollback(any()) }
        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            PythonLostSearchResponse(listOf(candidate(1))),
            PythonLostSearchResponse(listOf()),
        )
        every { animals.findWithShelterByIdIn(any()) } throws failure

        // when
        val thrown = catchThrowable { service.search(request()) }
        val next = service.search(request())

        // then
        assertThat(thrown).isSameAs(failure)
        verify(exactly = 1) { reader.rollback(transaction) }
        assertThat(next.candidates).isEmpty()
    }

    @Test
    fun `파이썬 줄 크기만큼 진행 중일 때__동시에 검색하면__모두 파이썬에 보내고 그다음 하나만 파이썬 요청 없이 503이며 완료 뒤 허가가 돌아온다`() {
        // given
        val inFlight = LostSearchService.MAX_IN_FLIGHT
        val entered = CountDownLatch(inFlight)
        val release = CountDownLatch(1)

        every { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) } answers {
            entered.countDown()
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue()
            PythonLostSearchResponse(listOf())
        }

        Executors.newFixedThreadPool(inFlight).use { executor ->
            // when
            val running = (1..inFlight).map { executor.submit(Callable { service.search(request()) }) }
            val busy: Throwable?
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                busy = catchThrowable { service.search(request()) }
            } finally {
                release.countDown()
            }
            val completed = running.map { it.get(5, TimeUnit.SECONDS) }
            val next = service.search(request())

            // then
            assertThat(inFlight).isEqualTo(11)
            assertStatus(busy, 503)
            assertThat(completed).allSatisfy { assertThat(it.candidates).isEmpty() }
            assertThat(next.candidates).isEmpty()
        }
        verify(exactly = inFlight + 1) { client.search(any(), any<FormData>(), any(), any(), any(), any(), any(), any()) }
        confirmVerified(reader, animals)
    }

    companion object {
        private const val KEY = "synthetic-lost-search-private-key"
        private const val EVIDENCE = "REGISTERED_DESCRIPTION_TEXT_MATCH"

        // 이전 네이티브처럼 필드가 없거나, 10건을 넘거나, 항목이 깨진 경우.
        @JvmStatic
        fun invalidDeceased(): Stream<List<PythonLostSearchResponse.Candidate?>?> = Stream.of(
            null,
            Collections.nCopies(11, PythonLostSearchResponse.Candidate(1L, .5, listOf())),
            listOf<PythonLostSearchResponse.Candidate?>(null),
            listOf(PythonLostSearchResponse.Candidate(1L, Double.NaN, listOf())),
        )

        @JvmStatic
        fun invalidResponses(): Stream<PythonLostSearchResponse?> = Stream.of(
            null,
            PythonLostSearchResponse(null),
            PythonLostSearchResponse(Collections.nCopies(21, PythonLostSearchResponse.Candidate(1L, .5, listOf()))),
            PythonLostSearchResponse(listOf<PythonLostSearchResponse.Candidate?>(null)),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(null, .5, listOf()))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(0L, .5, listOf()))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, null, listOf()))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, Double.NaN, listOf()))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, Double.POSITIVE_INFINITY, listOf()))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, .5, null))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, .5, listOf<String?>(null)))),
            PythonLostSearchResponse(listOf(PythonLostSearchResponse.Candidate(1L, .5, listOf("UNRECOGNIZED_EVIDENCE")))),
        )
    }
}
