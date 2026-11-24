package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import com.pawbridge.backend.animal.internal.statistics.infrastructure.JdbcAnimalStatistics
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The animal statistics queries on the real tables, with a fixed Seoul clock (noon on 2026-03-10). */
class AnimalStatisticsRepositoryContractTest : AnimalRepositoryContractBase() {
    private val today = LocalDate.parse("2026-03-10")
    private lateinit var statistics: AnimalStatistics

    @BeforeEach
    fun build() {
        statistics = JdbcAnimalStatistics(source, manager, Clock.fixed(Instant.parse("2026-03-10T03:00:00Z"), ZoneId.of("Asia/Seoul")))
        seedShelter(1, address = "서울특별시 강남구 역삼동")
        seedShelter(2, address = "서울 서초구")
        seedShelter(3, address = "경상북도 포항시")
        seedShelter(4, address = "  ")
        seedShelter(5, address = null)
    }

    @Test
    fun `오늘 구조된 동물과 오늘 입양으로 바뀐 동물이 있을 때__오늘을 읽으면__각각의 수를 센다`() {
        // given
        seedAnimal(10, 1, happenDate = "2026-03-10")
        seedAnimal(11, 1, happenDate = "2026-03-10", status = "ADOPTED", apmsUpdatedAt = "2026-03-10 08:00:00")
        seedAnimal(12, 1, happenDate = "2026-03-09", status = "ADOPTED", apmsUpdatedAt = "2026-03-10 23:00:00")
        seedAnimal(13, 1, happenDate = "2026-03-09", status = "ADOPTED", apmsUpdatedAt = "2026-03-09 23:59:59")
        seedAnimal(14, 1, happenDate = "2026-03-09", status = "PROTECT", apmsUpdatedAt = "2026-03-10 08:00:00")

        // when
        val result = statistics.today()

        // then
        assertThat(result).isEqualTo(AnimalStatistics.Today(today, 2, 2))
    }

    @Test
    fun `기간 안팎의 동물이 있을 때__상태별로 읽으면__기본 30일을 포함해 많은 순서로 센다`() {
        // given
        seedAnimal(10, 1, happenDate = "2026-03-10", status = "PROTECT")
        seedAnimal(11, 1, happenDate = "2026-02-09", status = "PROTECT")
        seedAnimal(12, 1, happenDate = "2026-02-09", status = "NOTICE")
        seedAnimal(13, 1, happenDate = "2026-02-09", status = "NOTICE")
        seedAnimal(14, 1, happenDate = "2026-02-09", status = "NOTICE")
        seedAnimal(15, 1, happenDate = "2026-02-08", status = "ADOPTED")
        seedAnimal(16, 1, happenDate = null, status = "ADOPTED")

        // when
        val byDefault = statistics.status(null, null)
        val custom = statistics.status(LocalDate.parse("2026-02-08"), LocalDate.parse("2026-02-08"))

        // then
        assertThat(byDefault.map { it.status!!.name to it.count }).isEqualTo(listOf("NOTICE" to 3L, "PROTECT" to 2L))
        assertThat(custom.map { it.status!!.name to it.count }).isEqualTo(listOf("ADOPTED" to 1L))
    }

    @Test
    fun `주소 첫 단어가 다른 보호소들일 때__지역별로 읽으면__표기를 합쳐 많은 순서로 세고 빈 주소는 뺀다`() {
        // given
        seedAnimal(10, 1)
        seedAnimal(11, 1)
        seedAnimal(12, 2)
        seedAnimal(19, 2)
        seedAnimal(13, 3)
        seedAnimal(14, 3)
        seedAnimal(15, 3)
        seedAnimal(16, 4)
        seedAnimal(17, 5)
        seedAnimal(18, 3, happenDate = "2025-01-01")

        // when
        val regions = statistics.regional(null, null)

        // then
        assertThat(regions.map { it.region to it.count }).isEqualTo(listOf("서울" to 4L, "경북" to 3L))
    }

    @Test
    fun `보호소 주소가 전남광주통합특별시일 때__지역별로 읽으면__광주의 구는 광주로, 나머지 시군은 전남으로 센다`() {
        // given
        seedShelter(6, address = "전남광주통합특별시 북구 본촌동")
        seedShelter(7, address = "전남광주통합특별시 여수시 소라면")
        seedShelter(8, address = "전라남도 나주시 금천면")
        seedAnimal(20, 6)
        seedAnimal(21, 7)
        seedAnimal(22, 8)
        seedAnimal(23, 8)

        // when
        val regions = statistics.regional(null, null)

        // then
        assertThat(regions.map { it.region to it.count }).isEqualTo(listOf("전남" to 3L, "광주" to 1L))
    }

    @Test
    fun `서울 날짜 경계의 등록 시각일 때__일별 등록을 읽으면__아홉 시간을 더한 날짜로 묶는다`() {
        // given
        seedAnimal(10, 1, createdAt = "2026-03-08 14:59:59")
        seedAnimal(11, 1, createdAt = "2026-03-08 15:00:00")
        seedAnimal(12, 1, createdAt = "2026-03-09 03:00:00")
        seedAnimal(13, 1, createdAt = "2026-03-04 10:00:00")

        // when
        val daily = statistics.dailyAnimals(LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-09"))

        // then
        assertThat(daily.map { it.date.toString() to it.count }).isEqualTo(listOf("2026-03-08" to 1L, "2026-03-09" to 2L))
    }

    @Test
    fun `기간 앞날에도 동물이 있을 때__입소 추이를 읽으면__전날 수를 따로 주고 기간만 날짜순으로 준다`() {
        // given
        seedAnimal(10, 1, happenDate = "2026-03-04")
        seedAnimal(11, 1, happenDate = "2026-03-05")
        seedAnimal(12, 1, happenDate = "2026-03-05")
        seedAnimal(13, 1, happenDate = "2026-03-07")
        seedAnimal(14, 1, happenDate = "2026-03-08")

        // when
        val trend = statistics.intakeTrend(LocalDate.parse("2026-03-05"), LocalDate.parse("2026-03-07"))

        // then
        assertThat(trend.previousDayCount).isEqualTo(1)
        assertThat(trend.daily!!.map { it.date.toString() to it.count }).isEqualTo(listOf("2026-03-05" to 2L, "2026-03-07" to 1L))
    }

    @Test
    fun `기간이 없거나 뒤집혔거나 미래이거나 366일 이상일 때__입소 추이를 읽으면__조회하지 않고 거부한다`() {
        // given
        // when
        // then
        for ((start, end) in listOf(
            null to null,
            LocalDate.parse("2026-03-07") to LocalDate.parse("2026-03-05"),
            LocalDate.parse("2026-03-05") to LocalDate.parse("2026-03-11"),
            LocalDate.parse("2025-03-09") to today,
        )) {
            assertThatThrownBy { statistics.intakeTrend(start, end) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(statistics.intakeTrend(LocalDate.parse("2025-03-11"), today).daily).isEmpty()
    }
}
