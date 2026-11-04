package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchRequest
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.PythonLostSearchClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.mock.web.MockMultipartFile
import org.springframework.orm.jpa.JpaTransactionManager

/** The lost-pet search: the remote ranking is a mock, the current animals, statuses and shelters come from the real tables. */
class LostSearchCurrentStateJpaContractTest : JpaContractBase() {
    @Autowired
    @Qualifier("animalCommandAnimals")
    lateinit var repository: AnimalRepository

    @Autowired
    @Qualifier("animalCommandJpaTransactionManager")
    lateinit var transactions: JpaTransactionManager

    @Autowired
    lateinit var mapper: ObjectMapper

    private lateinit var python: HttpServer
    private var answer = "{\"candidates\":[]}"
    private lateinit var search: LostSearchService

    @BeforeEach
    fun seed() {
        // The remote ranking service is the only mock: a local HTTP server that answers with the ranked ids.
        python = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        python.createContext("/internal/animals/lost-candidates") { exchange ->
            exchange.requestBody.readAllBytes()
            val body = answer.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        python.start()
        val client = PythonLostSearchClient("http://127.0.0.1:${python.address.port}", mapper)
        search = LostSearchService(client, repository, AnimalMapper(), "contract-key", transactions)
        seedShelter(SHELTER, "CR-S")
        seedAnimal(PROTECTED_DOG, SHELTER, "PROTECT", "DOG")
        seedAnimal(ADOPTED_DOG, SHELTER, "ADOPTED", "DOG")
        seedAnimal(NOTICE_DOG, SHELTER, "NOTICE", "DOG")
        seedAnimal(PROTECTED_CAT, SHELTER, "PROTECT", "CAT")
        seedAnimal(DECEASED_DOG, SHELTER, "NATURAL_DEATH", "DOG")
    }

    @AfterEach
    fun stop() {
        python.stop(0)
    }

    private fun remote(vararg ids: Long) {
        answer = """{"candidates":[${ids.joinToString(",") { """{"animalId":$it,"imageScore":0.5,"matchedEvidence":["DISCOVERY_PLACE_TEXT_MATCH"]}""" }}]}"""
    }

    private fun request(includeResolved: Boolean = false) = LostSearchRequest().apply {
        image = MockMultipartFile("image", "p.jpg", "image/jpeg", ByteArray(8) { 1 })
        species = Species.DOG
        includeAdoptedOrReturned = includeResolved
    }

    private fun ids(includeResolved: Boolean) =
        search.search(request(includeResolved)).candidates.map { it.animal.id }

    @Test
    fun `원격 후보가 현재 상태와 다를 때__검색하면__원격 순서를 지키되 보호 중인 같은 축종 동물만 현재 보호소 정보와 준다`() {
        // given
        remote(NOTICE_DOG, GONE, PROTECTED_CAT, ADOPTED_DOG, PROTECTED_DOG)

        // when
        val active = search.search(request())

        // then
        assertThat(active.candidates.map { it.animal.id }).isEqualTo(listOf(NOTICE_DOG, PROTECTED_DOG))
        assertThat(active.candidates.first().shelterPhone).isEqualTo("02-000-0000")
        assertThat(active.candidates.first().matchedEvidence).isEqualTo(listOf("DISCOVERY_PLACE_TEXT_MATCH"))
    }

    @Test
    fun `사망 기록을 켰을 때__검색하면__사망 기록은 지금 사망 상태인 같은 축종만 따로 준다`() {
        // given
        answer = """{"candidates":[{"animalId":$PROTECTED_DOG,"imageScore":0.5,"matchedEvidence":[]}],""" +
            """"deceasedCandidates":[{"animalId":$DECEASED_DOG,"imageScore":0.6,"matchedEvidence":[]},""" +
            """{"animalId":$ADOPTED_DOG,"imageScore":0.4,"matchedEvidence":[]}]}"""

        // when
        val result = search.search(request().apply { includeDeceased = true })

        // then
        assertThat(result.candidates.map { it.animal.id }).isEqualTo(listOf(PROTECTED_DOG))
        assertThat(result.deceasedCandidates.map { it.animal.id }).isEqualTo(listOf(DECEASED_DOG))
        assertThat(result.deceasedCandidates.first().shelterPhone).isEqualTo("02-000-0000")
    }

    @Test
    fun `입양된 동물이 후보에 있을 때__종료 동물 포함을 켜고 끄면__켰을 때만 입양 동물이 들어간다`() {
        // given
        remote(ADOPTED_DOG, PROTECTED_DOG)

        // when
        // then
        assertThat(ids(false)).isEqualTo(listOf(PROTECTED_DOG))
        assertThat(ids(true)).isEqualTo(listOf(ADOPTED_DOG, PROTECTED_DOG))
        execute("UPDATE pawbridge_animal.animals SET status = 'ADOPTED' WHERE id = ?", PROTECTED_DOG)
        assertThat(ids(false)).`as`("The current status decides, not the status when the remote ranked it").isEmpty()
    }

    private companion object {
        const val SHELTER = 8_500_001L
        const val PROTECTED_DOG = 8_500_101L
        const val ADOPTED_DOG = 8_500_102L
        const val NOTICE_DOG = 8_500_103L
        const val PROTECTED_CAT = 8_500_104L
        const val DECEASED_DOG = 8_500_105L
        const val GONE = 8_500_199L
    }
}
