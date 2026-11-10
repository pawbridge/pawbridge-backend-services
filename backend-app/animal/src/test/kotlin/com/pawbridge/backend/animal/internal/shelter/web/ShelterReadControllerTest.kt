package com.pawbridge.backend.animal.internal.shelter.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.animal.internal.shelter.application.ShelterNotFoundException
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Details
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Discovery
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Observation
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Order
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Page
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.PageRequest
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Preview
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.PublicInformation
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Search
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Summary
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.stream.Stream

class ShelterReadControllerTest {
    private val shelters: ShelterReads = mockk<ShelterReads>()
    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ShelterReadController(shelters), ShelterDiscoveryReadController(shelters))
        .setControllerAdvice(ShelterReadExceptionHandler())
        .setCustomArgumentResolvers(PageableHandlerMethodArgumentResolver())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()

    @Test
    fun `일반 목록일 때__목록을 조회하면__기본 페이지와 정렬과 전체 페이지 메타데이터와 요약 7개 필드를 유지한다`() {
        // given
        val created = LocalDateTime.of(2026, 9, 29, 13, 4, 5)

        every { shelters.search(any(), any()) } returns
            Page(listOf(Summary(4L, "reg-4", "센터", "02", "서울", "시청", created)), 1)

        // when
        val actual: JsonNode = response("/api/v1/shelters")

        // then
        verify(exactly = 1) {
            shelters.search(Search(null, null, null, null), PageRequest(0, 20, listOf(Order("name", true, false))))
        }
        assertThat(actual.properties().map { entry -> entry.key }).containsExactlyInAnyOrder(
            "content", "pageable", "last", "totalPages", "totalElements",
            "size", "number", "sort", "first", "numberOfElements", "empty",
        )
        assertThat(actual.path("content").get(0)).isEqualTo(
            mapper.readTree(
                """
                {"id":4,"careRegNo":"reg-4","name":"센터","phone":"02","address":"서울","organizationName":"시청","createdAt":"2026-09-29T13:04:05"}
                """.trimIndent(),
            ),
        )
        assertThat(actual.path("size").asInt()).isEqualTo(20)
        assertThat(actual.path("totalElements").asLong()).isOne()
        assertThat(actual.path("pageable").path("pageSize").asInt()).isEqualTo(20)
        assertThat(actual.path("sort").path("sorted").asBoolean()).isTrue()
        assertThat(actual.path("first").asBoolean()).isTrue()
        assertThat(actual.path("last").asBoolean()).isTrue()
    }

    @Test
    fun `모든 필터와 사용자 지정 페이지 정렬이 있을 때__목록을 조회하면__필터를 합치지 않고 원본 검색어와 모든 정렬을 전달한다`() {
        // given
        every { shelters.search(any(), any()) } returns Page(listOf(), 0)

        // when
        val result = mvc.perform(
            get("/api/v1/shelters").param("name", "name").param("keyword", " 센터 ").param("address", " 서울 ")
                .param("organizationName", "org").param("page", "2").param("size", "12")
                .param("sort", "name,desc,ignorecase", "createdAt,asc"),
        )

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) {
            shelters.search(
                Search("name", " 서울 ", "org", " 센터 "),
                PageRequest(
                    2,
                    12,
                    listOf(Order("name", false, true), Order("createdAt", true, false)),
                ),
            )
        }
    }

    @Test
    fun `상세와 등록번호로 조회할 때__읽으면__14개 필드와 원래의 공개 정보를 모두 돌려준다`() {
        // given
        val created = LocalDateTime.of(2026, 9, 29, 13, 4, 5)
        val updated = created.plusDays(1)
        val publicInformation = PublicInformation(
            "APMS_SHELTER_INFO", "02-public", 37.5, 127.1, "09", "18", "10", "16", "월", "2026-09-29", updated,
        )
        val detail = Details(
            4L, "reg-4", "센터", "02", "서울", "대표", "시청", "sample@example.test", "소개", "절차", "09-18",
            created, updated, publicInformation,
        )
        val expected: JsonNode = mapper.readTree(
            """
            {"id":4,"careRegNo":"reg-4","name":"센터","phone":"02","address":"서울","ownerName":"대표","organizationName":"시청",
            "email":"sample@example.test","introduction":"소개","adoptionProcedure":"절차","operatingHours":"09-18",
            "createdAt":"2026-09-29T13:04:05","updatedAt":"2026-09-30T13:04:05",
            "publicInformation":{"source":"APMS_SHELTER_INFO","phone":"02-public","latitude":37.5,"longitude":127.1,
            "weekdayOpen":"09","weekdayClose":"18","weekendOpen":"10","weekendClose":"16","closedDays":"월",
            "sourceUpdatedDate":"2026-09-29","collectedAt":"2026-09-30T13:04:05"}}
            """.trimIndent(),
        )

        every { shelters.findById(any()) } returns detail
        every { shelters.findByCareRegNo(any()) } returns detail

        // when
        val byId = response("/api/v1/shelters/4")
        val byRegistration = response("/api/v1/shelters/by-care-reg-no/reg-4")

        // then
        assertThat(byId).isEqualTo(expected)
        assertThat(byRegistration).isEqualTo(expected)
        verify(exactly = 1) { shelters.findById(4L) }
        verify(exactly = 1) { shelters.findByCareRegNo("reg-4") }
    }

    @Test
    fun `상세의 선택 필드가 null이고 건수만 읽을 때__읽으면__null을 생략하고 봉투 없이 Long을 돌려준다`() {
        // given
        every { shelters.findById(any()) } returns
            Details(4L, "reg-4", "센터", null, null, null, null, null, null, null, null, null, null, null)

        // when
        val detail = response("/api/v1/shelters/4")

        // then
        assertThat(detail).isEqualTo(mapper.readTree("{\"id\":4,\"careRegNo\":\"reg-4\",\"name\":\"센터\"}"))
        verify(exactly = 1) { shelters.findById(4L) }

        // given
        every { shelters.count() } returns 9876543210L

        // when
        val count = response("/api/v1/shelters/count")

        // then
        assertThat(count.asLong()).isEqualTo(9876543210L)
        verify(exactly = 1) { shelters.count() }
    }

    @Test
    fun `탐색 날짜와 필터와 페이지가 있을 때__탐색하면__원래 입력을 바인딩하고 null 가능한 미리보기 필드를 포함한다`() {
        // given
        every { shelters.discover(any(), any(), any(), any(), any(), any()) } returns
            Page(
                listOf(
                    Discovery(
                        4, "reg", "센터", null, null, 1,
                        listOf(Preview(40, null, "DOG", "UNKNOWN", null, null, LocalDate.of(2026, 9, 29))),
                    ),
                ),
                99,
            )

        // when
        val result = mvc.perform(
            get("/api/v1/shelters/discovery").param("intakeFrom", "2026-09-01").param("intakeTo", "2026-09-30")
                .param("keyword", "보호소").param("address", "서울").param("page", "2"),
        ).andExpect(status().isOk()).andReturn().response

        // then
        verify(exactly = 1) {
            shelters.discover("보호소", "서울", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 2, 12)
        }
        val actual: JsonNode = mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8))
        assertThat(actual.path("content").get(0)).isEqualTo(
            mapper.readTree(
                """
                {"id":4,"careRegNo":"reg","name":"센터","address":null,"phone":null,"protectedCount":1,
                "animals":[{"id":40,"breed":null,"species":"DOG","gender":"UNKNOWN","birthYear":null,"imageUrl":null,"happenDate":"2026-09-29"}]}
                """.trimIndent(),
            ),
        )
        assertThat(actual.path("number").asInt()).isEqualTo(2)
        assertThat(actual.path("size").asInt()).isEqualTo(12)
        assertThat(actual.path("totalElements").asLong()).isEqualTo(99)
        assertThat(actual.path("sort").path("unsorted").asBoolean()).isTrue()
    }

    @Test
    fun `접수일이 없을 때__탐색하면__서울 기준 오늘로 끝나는 30일 포함 구간을 기본으로 쓴다`() {
        // given
        every { shelters.discover(any(), any(), any(), any(), any(), any()) } returns Page(listOf(), 0)

        // when
        response("/api/v1/shelters/discovery")

        // then
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        verify(exactly = 1) { shelters.discover("", "", today.minusDays(29), today, 0, 12) }
    }

    @Test
    fun `접수일 한쪽 경계만 있을 때__탐색하면__원래의 구간 검증을 위해 다른 경계를 null로 전달한다`() {
        // given
        every { shelters.discover(any(), any(), any(), any(), any(), any()) } throws
            IllegalArgumentException("접수일은 시작일과 종료일을 함께 지정하고 최대 366일 이내로 선택해주세요.")

        // when
        val result = mvc.perform(get("/api/v1/shelters/discovery").param("intakeFrom", "2026-09-01"))

        // then
        result.andExpect(status().isBadRequest())
        verify(exactly = 1) { shelters.discover("", "", LocalDate.of(2026, 9, 1), null, 0, 12) }
    }

    @ParameterizedTest
    @MethodSource("malformed")
    fun `탐색 파라미터가 잘못됐을 때__바인딩하면__애플리케이션을 호출하지 않고 로컬의 빈 400을 유지한다`(
        route: String,
        parameter: String?,
        value: String?,
    ) {
        // given
        val request = get(route)
        if (parameter != null) {
            request.param(parameter, value)
        }

        // when
        val result = mvc.perform(request).andExpect(status().isBadRequest()).andReturn().response

        // then
        assertThat(result.contentAsString).isEmpty()
        confirmVerified(shelters)
    }

    @Test
    fun `기록된 관측이 있거나 빈 시계열일 때__읽으면__날짜와 오프셋과 없는 날을 0으로 채우지 않고 유지한다`() {
        // given
        val observed = Observation(LocalDate.of(2026, 9, 3), OffsetDateTime.parse("2026-09-03T10:11:12+09:00"), 8)
        val route = "/api/v1/shelters/4/observations?from=2026-09-01&to=2026-09-30"

        every { shelters.observations(any(), any(), any()) } returns listOf(observed)

        // when
        val recorded = response(route)

        // then
        assertThat(recorded).isEqualTo(
            mapper.readTree("[{\"date\":\"2026-09-03\",\"observedAt\":\"2026-09-03T10:11:12+09:00\",\"protectedCount\":8}]"),
        )

        // given
        every { shelters.observations(any(), any(), any()) } returns listOf()

        // when
        val empty = response(route)

        // then
        assertThat(empty).isEqualTo(mapper.createArrayNode())
        verify(exactly = 2) { shelters.observations(4L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)) }
    }

    @Test
    fun `관측 날짜 파라미터가 빠졌을 때__바인딩하면__전역 400 봉투를 유지한다`() {
        // given
        // when
        val result = mvc.perform(get("/api/v1/shelters/4/observations").param("to", "2026-09-30"))
            .andExpect(status().isBadRequest()).andReturn().response

        // then
        assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8))).isEqualTo(
            mapper.createObjectNode().put("code", 400).put("message", "필수 요청 파라미터 'from'(이)가 누락되었습니다.").putNull("data"),
        )
        confirmVerified(shelters)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `읽기가 실패할 때__조회하면__원래 상태와 안전한 응답 봉투를 유지한다`(
        failure: RuntimeException,
        observations: Boolean,
        code: Int,
        message: String,
    ) {
        // given
        val route: String
        if (observations) {
            every { shelters.observations(any(), any(), any()) } throws failure
            route = "/api/v1/shelters/4/observations?from=2026-09-01&to=2026-09-30"
        } else {
            every { shelters.findById(any()) } throws failure
            route = "/api/v1/shelters/4"
        }

        // when
        val result = mvc.perform(get(route)).andExpect(status().`is`(code)).andReturn().response

        // then
        assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8)))
            .isEqualTo(mapper.createObjectNode().put("code", code).put("message", message).putNull("data"))
        if (observations) {
            verify(exactly = 1) { shelters.observations(4L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)) }
        } else {
            verify(exactly = 1) { shelters.findById(4L) }
        }
    }

    @Test
    fun `일반 상세 ID가 잘못됐을 때__바인딩하면__일반 500을 유지하고 애플리케이션을 호출하지 않는다`() {
        // given
        // when
        val result = mvc.perform(get("/api/v1/shelters/invalid"))

        // then
        result.andExpect(status().isInternalServerError())
        confirmVerified(shelters)
    }

    @Test
    fun `등록번호로 찾은 보호소가 없을 때__조회하면__404 봉투를 준다`() {
        // given
        every { shelters.findByCareRegNo(any()) } returns null

        // when
        val result = mvc.perform(get("/api/v1/shelters/by-care-reg-no/reg-none"))
            .andExpect(status().isNotFound()).andReturn().response

        // then
        assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8))).isEqualTo(
            mapper.createObjectNode().put("code", 404).put("message", "보호소를 찾을 수 없습니다").putNull("data"),
        )
        verify(exactly = 1) { shelters.findByCareRegNo("reg-none") }
    }

    private fun response(route: String): JsonNode {
        val result = mvc.perform(get(route)).andExpect(status().isOk()).andReturn().response
        return mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8))
    }

    companion object {
        @JvmStatic
        fun malformed(): Stream<Arguments> = Stream.of(
            Arguments.of("/api/v1/shelters/discovery", "intakeFrom", "not-a-date"),
            Arguments.of("/api/v1/shelters/discovery", "page", "not-a-number"),
            Arguments.of("/api/v1/shelters/invalid/observations?from=2026-09-01&to=2026-09-30", null, null),
        )

        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(IllegalStateException("Shelter not found: 4"), false, 500, "서버 내부 오류가 발생했습니다."),
            Arguments.of(ShelterNotFoundException(), true, 404, "보호소를 찾을 수 없습니다"),
            Arguments.of(IllegalArgumentException("invalid range"), true, 400, "invalid range"),
            Arguments.of(
                DataAccessResourceFailureException("synthetic private detail"),
                false,
                500,
                "데이터베이스 오류가 발생했습니다.",
            ),
        )
    }
}
