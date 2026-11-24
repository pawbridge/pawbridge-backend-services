package com.pawbridge.backend.animal.internal.statistics.infrastructure

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.DailyCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.IntakeTrend
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.RegionalCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.Status
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.StatusCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.Today
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

/** Original JPQL aggregate predicates and service rules adapted to explicit PostgreSQL SELECTs. */
class JdbcAnimalStatistics : AnimalStatistics {
    private val jdbc: NamedParameterJdbcOperations
    private val reads: TransactionOperations
    private val clock: Clock

    constructor(source: DataSource, manager: PlatformTransactionManager) : this(source, manager, Clock.system(SEOUL))

    constructor(source: DataSource, manager: PlatformTransactionManager, clock: Clock) {
        this.jdbc = NamedParameterJdbcTemplate(JdbcTemplate(source))
        val transaction = TransactionTemplate(manager)
        transaction.isReadOnly = true
        this.reads = transaction
        this.clock = clock.withZone(SEOUL)
    }

    // Package-private in Java; public on the JVM here because Kotlin has no package-private.
    internal constructor(jdbc: NamedParameterJdbcOperations, reads: TransactionOperations, clock: Clock) {
        this.jdbc = jdbc
        this.reads = reads
        this.clock = clock.withZone(SEOUL)
    }

    override fun today(): Today {
        return reads.execute {
            val today = LocalDate.now(clock)
            val parameters = MapSqlParameterSource("today", today).addValue("status", Status.ADOPTED.name)
            val rescued = jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_animal.animals WHERE happen_date=:today",
                parameters,
                Long::class.javaObjectType,
            )
            val adopted = jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_animal.animals WHERE status=:status AND CAST(apms_updated_at AS date)=:today",
                parameters,
                Long::class.javaObjectType,
            )
            Today(today, rescued, adopted)
        }!!
    }

    override fun status(startDate: LocalDate?, endDate: LocalDate?): List<StatusCount> {
        return reads.execute {
            val range = resolveDefaultDateRange(startDate, endDate)
            jdbc.query(
                """
                SELECT status, count(*) AS animal_count FROM pawbridge_animal.animals
                WHERE happen_date BETWEEN :startDate AND :endDate
                GROUP BY status ORDER BY count(*) DESC
                """.trimIndent() + "\n",
                parameters(range[0], range[1]),
                RowMapper { row, _ ->
                    StatusCount(
                        Status.valueOf(row.getString("status")),
                        row.getObject("animal_count", Long::class.javaObjectType),
                    )
                },
            )
        }!!
    }

    override fun regional(startDate: LocalDate?, endDate: LocalDate?): List<RegionalCount> {
        return reads.execute {
            val range = resolveDefaultDateRange(startDate, endDate)
            val shelterStats: List<ShelterCount> = jdbc.query(
                """
                SELECT s.address, count(a.id) AS animal_count
                FROM pawbridge_animal.animals a JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id
                WHERE a.happen_date BETWEEN :startDate AND :endDate
                GROUP BY s.id ORDER BY count(a.id) DESC
                """.trimIndent() + "\n",
                parameters(range[0], range[1]),
                RowMapper { row, _ ->
                    ShelterCount(
                        row.getString("address"),
                        row.getObject("animal_count", Long::class.javaObjectType),
                    )
                },
            )
            val regions = LinkedHashMap<String, Long>()
            for (shelter in shelterStats) {
                val address = shelter.address
                if (address == null || isBlank(address)) {
                    continue
                }
                val words = address.split(" ")
                val rawRegion = words[0]
                val district = words.getOrNull(1)
                // Map.merge rejects a null value with NullPointerException; `!!` keeps that.
                regions.merge(normalizeRegion(rawRegion, district), shelter.count!!) { left, right -> left + right }
            }
            regions.entries
                .sortedByDescending { entry -> entry.value }
                .map { entry -> RegionalCount(entry.key, entry.value) }
        }!!
    }

    override fun dailyAnimals(startDate: LocalDate?, endDate: LocalDate?): List<DailyCount> {
        return reads.execute {
            daily("CAST(a.created_at + INTERVAL '9 hours' AS date)", startDate, endDate)
        }!!
    }

    override fun intakeTrend(startDate: LocalDate?, endDate: LocalDate?): IntakeTrend {
        return reads.execute {
            if (startDate == null ||
                endDate == null ||
                startDate.isAfter(endDate) ||
                endDate.isAfter(LocalDate.now(clock)) ||
                ChronoUnit.DAYS.between(startDate, endDate) >= 366
            ) {
                throw IllegalArgumentException("오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.")
            }
            val previousDate = startDate.minusDays(1)
            val rows = daily("a.happen_date", previousDate, endDate)
            val previous = rows.filter { row -> row.date!! == previousDate }.sumOf { row -> row.count!! }
            IntakeTrend(
                startDate,
                endDate,
                rows.filter { row -> !row.date!!.isBefore(startDate) },
                previous,
            )
        }!!
    }

    private fun daily(expression: String, startDate: LocalDate?, endDate: LocalDate?): List<DailyCount> {
        return jdbc.query(
            "SELECT " + expression + " AS stat_date,count(*) AS animal_count FROM pawbridge_animal.animals a WHERE " +
                expression + " BETWEEN :startDate AND :endDate GROUP BY " + expression + " ORDER BY " + expression,
            parameters(startDate, endDate),
            RowMapper { row, _ ->
                DailyCount(
                    row.getObject("stat_date", LocalDate::class.java),
                    row.getObject("animal_count", Long::class.javaObjectType),
                )
            },
        )
    }

    private fun resolveDefaultDateRange(startDate: LocalDate?, endDate: LocalDate?): Array<LocalDate> {
        val end = endDate ?: LocalDate.now(clock)
        val start = startDate ?: end.minusDays(29)
        return arrayOf(start, end)
    }

    private class ShelterCount(
        val address: String?,
        val count: Long?,
    )

    private companion object {
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        private fun parameters(start: LocalDate?, end: LocalDate?): MapSqlParameterSource {
            return MapSqlParameterSource("startDate", start).addValue("endDate", end)
        }

        // 전남광주통합특별시 주소에서 옛 광주광역시에 속하는 구. 나머지 시군은 옛 전라남도로 센다.
        private val GWANGJU_DISTRICTS: Set<String> = setOf("동구", "서구", "남구", "북구", "광산구")

        /** Preserve original first-token/contains mapping and its precedence, including unknown tokens. */
        private fun normalizeRegion(rawRegion: String?, district: String?): String {
            if (rawRegion == null) return "기타"
            // 2026-07-01 광주광역시와 전라남도가 전남광주통합특별시로 합쳐졌다. 통계 지도는 옛 두 지역으로 나눠 보여 주므로 구 이름으로 가른다.
            if (rawRegion == "전남광주통합특별시") {
                return if (district in GWANGJU_DISTRICTS) "광주" else "전남"
            }
            if (rawRegion.contains("서울")) return "서울"
            if (rawRegion.contains("부산")) return "부산"
            if (rawRegion.contains("대구")) return "대구"
            if (rawRegion.contains("인천")) return "인천"
            if (rawRegion.contains("광주")) return "광주"
            if (rawRegion.contains("대전")) return "대전"
            if (rawRegion.contains("울산")) return "울산"
            if (rawRegion.contains("세종")) return "세종"
            if (rawRegion.contains("경기")) return "경기"
            if (rawRegion.contains("강원")) return "강원"
            if (rawRegion.contains("충청북도") || rawRegion.contains("충북")) return "충북"
            if (rawRegion.contains("충청남도") || rawRegion.contains("충남")) return "충남"
            if (rawRegion.contains("전라북도") || rawRegion.contains("전북")) return "전북"
            if (rawRegion.contains("전라남도") || rawRegion.contains("전남")) return "전남"
            if (rawRegion.contains("경상북도") || rawRegion.contains("경북")) return "경북"
            if (rawRegion.contains("경상남도") || rawRegion.contains("경남")) return "경남"
            if (rawRegion.contains("제주")) return "제주"
            return rawRegion
        }
    }
}
