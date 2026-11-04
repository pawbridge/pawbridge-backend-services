package com.pawbridge.backend.animal.internal.lostsearch.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchRequest
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchResponse
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException

class LostSearchControllerTest {
    private val service = mockk<LostSearchService>()
    private val json = ObjectMapper()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(LostSearchController(service)).build()
    }

    @Test
    fun `이미지와 종류와 지역이 있는 멀티파트 요청일 때__후보를 검색하면__원래 요청으로 바인딩하고 이미지 점수 없는 후보를 돌려준다`() {
        // given
        val animal = AnimalDetailResponse.builder().id(7L).species(Species.DOG).build()

        every { service.search(any()) } returns LostSearchResponse(
            listOf(LostSearchResponse.Candidate(animal, "02-0000", listOf("DISCOVERY_PLACE_TEXT_MATCH"))),
        )

        // when
        val response = mvc.perform(
            multipart("/api/v1/animals/lost-candidates")
                .file(MockMultipartFile("image", byteArrayOf(1)))
                .param("species", "DOG")
                .param("region", "서울")
                .param("includeDeceased", "true"),
        ).andReturn().response

        // then
        assertThat(response.status).isEqualTo(200)
        val result = json.readTree(response.contentAsByteArray)
        val candidate = result.get("candidates").get(0)
        assertThat(candidate.get("animal").get("id").asLong()).isEqualTo(7L)
        assertThat(candidate.get("shelterPhone").asText()).isEqualTo("02-0000")
        assertThat(candidate.has("imageScore")).isFalse()
        assertThat(result.get("deceasedCandidates").isArray).isTrue()
        assertThat(result.get("deceasedCandidates")).isEmpty()
        val request = slot<LostSearchRequest>()
        verify(exactly = 1) { service.search(capture(request)) }
        assertThat(request.captured.species).isEqualTo(Species.DOG)
        assertThat(request.captured.image!!.bytes).isEqualTo(byteArrayOf(1))
        assertThat(request.captured.region).isEqualTo("서울")
        assertThat(request.captured.includeDeceased).isTrue()
        assertThat(request.captured.includeAdoptedOrReturned).isFalse()
    }

    @Test
    fun `잘못된 날짜가 들어올 때__후보를 검색하면__서비스 전에 원래의 400 메시지를 돌려준다`() {
        // given

        // when
        val response = mvc.perform(
            multipart("/api/v1/animals/lost-candidates")
                .file(MockMultipartFile("image", byteArrayOf(1)))
                .param("species", "DOG")
                .param("lostDate", "invalid-date"),
        ).andReturn().response

        // then
        assertThat(response.status).isEqualTo(400)
        assertThat(json.readTree(response.contentAsByteArray).get("message").asText())
            .isEqualTo("사진·종류·날짜·지역·특징 입력을 확인해 주세요")
        confirmVerified(service)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 413, 503])
    fun `서비스가 실패 상태를 던질 때__후보를 검색하면__상태와 메시지를 유지하고 503에만 재시도 헤더를 붙인다`(status: Int) {
        // given
        every { service.search(any()) } throws ResponseStatusException(HttpStatus.valueOf(status), "synthetic-safe-message")

        // when
        val response = mvc.perform(
            multipart("/api/v1/animals/lost-candidates")
                .file(MockMultipartFile("image", byteArrayOf(1)))
                .param("species", "DOG"),
        ).andReturn().response

        // then
        assertThat(response.status).isEqualTo(status)
        assertThat(json.readTree(response.contentAsByteArray).get("message").asText())
            .isEqualTo("synthetic-safe-message")
        assertThat(response.getHeader("Retry-After")).isEqualTo(if (status == 503) "3" else null)
    }
}
