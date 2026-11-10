package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.shelter.application.ShelterNotFoundException
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Order
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.PageRequest
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Search
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterObservationRecorder
import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterReads
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** The shelter list, detail, discovery and observation queries, and the daily observation recorder, on the real tables. */
class ShelterRepositoryContractTest : AnimalRepositoryContractBase() {
    @Autowired
    private lateinit var mapper: ObjectMapper

    private lateinit var shelters: ShelterReads

    @BeforeEach
    fun seed() {
        shelters = JdbcShelterReads(source, manager, mapper)
        seedShelter(1, name = "행복 보호소", address = "서울특별시 강남구 역삼동", careRegNo = "CR-1")
        seedShelter(2, name = "바다 보호소", address = "부산광역시 해운대구", careRegNo = "CR-2")
        seedShelter(3, name = "가 100%_ 센터", address = "서울특별시 서초구", careRegNo = "CR-3")
        execute("UPDATE pawbridge_animal.shelters SET organization_name = '재단 ' || id::text, created_at = ('2026-01-0' || id::text || ' 00:00:00')::timestamp")
    }

    private fun page(number: Int = 0, size: Int = 10, vararg orders: Order) = PageRequest(number, size, orders.toList())

    private fun none() = Search(null, null, null, null)

    @Test
    fun `이름과 주소와 기관 조건일 때__검색하면__포함 조건을 걸고 퍼센트와 밑줄은 글자로 취급한다`() {
        // given
        // when
        val byName = shelters.search(Search("보호소", null, null, null), page(0, 10, Order("id", true, false)))
        val byLiteral = shelters.search(Search("100%_", null, null, null), page())
        val byKeyword = shelters.search(Search(null, null, null, "서울"), page(0, 10, Order("id", true, false)))
        val both = shelters.search(Search(null, "서울", null, "행복"), page())
        val byOrganization = shelters.search(Search(null, null, "재단 2", null), page())

        // then
        assertThat(byName.content().map { it.id }).isEqualTo(listOf(1L, 2L))
        assertThat(byLiteral.content().map { it.id }).isEqualTo(listOf(3L))
        assertThat(byKeyword.content().map { it.id }).isEqualTo(listOf(1L, 3L))
        assertThat(both.content().map { it.id }).isEqualTo(listOf(1L))
        assertThat(byOrganization.content().map { it.id }).isEqualTo(listOf(2L))
    }

    @Test
    fun `정렬과 쪽 크기를 줄 때__검색하면__정렬대로 자르고 부분 쪽이면 총계를 추론하며 알 수 없는 정렬은 거부한다`() {
        // given
        // when
        val newest = shelters.search(none(), page(0, 2, Order("createdAt", false, false)))
        val second = shelters.search(none(), page(1, 2, Order("createdAt", false, false)))
        val beyond = shelters.search(none(), page(5, 2, Order("createdAt", false, false)))
        val byNameIgnoringCase = shelters.search(Search("보호소", null, null, null), page(0, 5, Order("name", true, true)))

        // then
        assertThat(newest.content().map { it.id }).isEqualTo(listOf(3L, 2L))
        assertThat(newest.totalElements()).isEqualTo(3)
        assertThat(second.content().map { it.id }).isEqualTo(listOf(1L))
        assertThat(second.totalElements()).isEqualTo(3)
        assertThat(beyond.content()).isEmpty()
        assertThat(beyond.totalElements()).isEqualTo(3)
        assertThat(byNameIgnoringCase.content().map { it.name }).isEqualTo(listOf("바다 보호소", "행복 보호소"))
        assertThatThrownBy { shelters.search(none(), page(0, 5, Order("password", true, false))) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `보호 동물이 있는 보호소를 동물 컬렉션으로 정렬할 때__검색하면__LIMIT 뒤에 루트를 중복 제거한다`() {
        // given
        seedAnimal(10, 1)
        seedAnimal(11, 1)
        seedAnimal(12, 1)
        seedAnimal(13, 2)

        // when
        val first = shelters.search(none(), page(0, 2, Order("animals.id", true, false)))

        // then
        assertThat(first.content().map { it.id }).`as`("Three joined rows of shelter 1 collapse to one root").isEqualTo(listOf(1L))
    }

    @Test
    fun `공개 정보가 있거나 없는 보호소일 때__상세를 읽으면__공개 정보를 붙여 주고 없는 보호소는 예외다`() {
        // given
        execute(
            "INSERT INTO pawbridge_animal.shelter_public_information(shelter_id, details, collected_at) VALUES " +
                "(1, '{\"phone\":\"02-111-2222\",\"latitude\":37.5,\"longitude\":127.0,\"weekdayOpen\":\"09:00\"}'::jsonb, '2026-03-02 03:04:05.5')",
        )

        // when
        val withInformation = shelters.findById(1)
        val byNumber = shelters.findByCareRegNo("CR-2")

        // then
        assertThat(withInformation.name).isEqualTo("행복 보호소")
        assertThat(withInformation.publicInformation!!.source).isEqualTo("APMS_SHELTER_INFO")
        assertThat(withInformation.publicInformation!!.latitude).isEqualTo(37.5)
        assertThat(withInformation.publicInformation!!.collectedAt).isEqualTo(LocalDateTime.parse("2026-03-02T03:04:05.500"))
        assertThat(byNumber!!.id).isEqualTo(2)
        assertThat(byNumber.publicInformation).isNull()
        assertThat(shelters.count()).isEqualTo(3)
        assertThatThrownBy { shelters.findById(999) }.isInstanceOf(ShelterNotFoundException::class.java)
        assertThatThrownBy { shelters.findByCareRegNo("none") }.isInstanceOf(ShelterNotFoundException::class.java)
    }

    @Test
    fun `보호 중 동물이 있는 보호소들일 때__탐색하면__보호 수 많은 순서로 묶고 보호소마다 최신 입소 셋만 미리 보여 준다`() {
        // given
        seedAnimal(10, 1, happenDate = "2026-03-01")
        seedAnimal(11, 1, happenDate = "2026-03-02")
        seedAnimal(12, 1, happenDate = "2026-03-03")
        seedAnimal(13, 1, happenDate = "2026-03-04")
        seedAnimal(14, 1, happenDate = "2026-03-05", status = "ADOPTED")
        seedAnimal(15, 1, happenDate = "2026-02-01")
        seedAnimal(16, 2, happenDate = "2026-03-02")
        seedAnimal(17, 3, happenDate = "2026-03-02")
        seedAnimal(18, 3, happenDate = "2026-03-03")
        val from = LocalDate.parse("2026-03-01")
        val to = LocalDate.parse("2026-03-31")

        // when
        val all = shelters.discover(null, null, from, to, 0, 2)
        val second = shelters.discover(null, null, from, to, 1, 2)
        val bySeoul = shelters.discover(" 서울 ", "서울", from, to, 0, 10)
        val literal = shelters.discover("100%_", null, from, to, 0, 10)

        // then
        assertThat(all.totalElements()).isEqualTo(3)
        assertThat(all.content().map { it.id to it.protectedCount }).isEqualTo(listOf(1L to 4L, 3L to 2L))
        assertThat(all.content().first().animals!!.map { it.id }).`as`("Newest three of shelter 1").isEqualTo(listOf(13L, 12L, 11L))
        assertThat(second.content().map { it.id }).isEqualTo(listOf(2L))
        assertThat(bySeoul.content().map { it.id }).isEqualTo(listOf(1L, 3L))
        assertThat(literal.content().map { it.id }).isEqualTo(listOf(3L))
    }

    @Test
    fun `기간이나 쪽 크기가 어긋날 때__탐색하면__질의 전에 거부한다`() {
        // given
        val from = LocalDate.parse("2026-03-01")

        // when
        // then
        assertThatThrownBy { shelters.discover(null, null, null, null, 0, 10) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { shelters.discover(null, null, from, from.plusDays(366), 0, 10) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { shelters.discover(null, null, from, from, 0, 25) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { shelters.discover(null, null, from, from, 417, 24) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `관측 기록이 있는 보호소일 때__관측을 읽으면__기간 안의 날짜순으로 주고 없는 보호소는 예외다`() {
        // given
        execute(
            "INSERT INTO pawbridge_animal.shelter_daily_observations(shelter_id, observation_date, observed_at, protected_count) VALUES " +
                "(1, '2026-03-02', '2026-03-02T10:00:00+09', 3), (1, '2026-03-01', '2026-03-01T10:00:00+09', 2), (1, '2026-04-01', '2026-04-01T10:00:00+09', 9)",
        )

        // when
        val observations = shelters.observations(1, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"))

        // then
        assertThat(observations.map { it.date.toString() to it.protectedCount }).isEqualTo(listOf("2026-03-01" to 2L, "2026-03-02" to 3L))
        assertThatThrownBy { shelters.observations(999, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31")) }
            .isInstanceOf(ShelterNotFoundException::class.java)
    }

    @Test
    fun `보호 중 동물이 있는 보호소들일 때__오늘 관측을 기록하면__보호소마다 한 행을 쓰고 다시 기록해도 처음 값을 지킨다`() {
        // given
        seedAnimal(10, 1)
        seedAnimal(11, 1)
        seedAnimal(12, 1, status = "ADOPTED")
        seedAnimal(13, 2)
        val recorder = JdbcShelterObservationRecorder(JdbcTemplate(writeSource), TransactionTemplate(writeManager))
        val seoulToday = java.time.LocalDate.now(ZoneId.of("Asia/Seoul"))

        // when
        val first = recorder.recordToday()
        seedAnimal(14, 1)
        val second = recorder.recordToday()

        // then
        assertThat(first).isEqualTo(3)
        assertThat(second).`as`("The day's observation is never rewritten").isZero()
        assertThat(
            ContractPostgres.admin.queryForList(
                "SELECT shelter_id || ':' || protected_count || ':' || observation_date FROM pawbridge_animal.shelter_daily_observations ORDER BY shelter_id",
                String::class.java,
            ),
        ).containsAnyOf("1:2:$seoulToday", "1:2:${seoulToday.minusDays(1)}")
        assertThat(count("SELECT count(*) FROM pawbridge_animal.shelter_daily_observations WHERE protected_count = 0")).`as`("A shelter without protected animals is observed as zero").isOne()
    }

    @Test
    fun `읽기 전용 트랜잭션 안에서__관측을 기록하면__데이터베이스가 쓰기를 거절하고 행이 남지 않는다`() {
        // given
        seedAnimal(10, 1)
        val readOnly = TransactionTemplate(writeManager).apply { isReadOnly = true }
        val recorder = JdbcShelterObservationRecorder(JdbcTemplate(writeSource), readOnly)

        // when
        // then
        assertNoSideEffects {
            assertThatThrownBy { recorder.recordToday() }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        }
    }
}
