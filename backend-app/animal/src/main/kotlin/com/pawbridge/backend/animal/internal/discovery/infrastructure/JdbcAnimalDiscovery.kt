package com.pawbridge.backend.animal.internal.discovery.infrastructure

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.ApiSource
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Details
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.NeuterStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Order
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Page
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.PageRequest
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Search
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Summary
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException
import com.pawbridge.backend.animal.internal.discovery.application.IntakeDateRange
import com.pawbridge.backend.animal.internal.discovery.application.SearchProjectionPendingException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.io.UncheckedIOException
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.regex.Pattern
import javax.sql.DataSource

/** Selected list SQL from PostgresqlAnimalQueryService; owns no search projection or writes. */
class JdbcAnimalDiscovery : AnimalDiscovery {
    private val jdbc: NamedParameterJdbcOperations
    private val transactions: TransactionOperations
    private val analyzer: KoreanSearchAnalyzer

    constructor(source: DataSource, manager: PlatformTransactionManager, analyzer: KoreanSearchAnalyzer) {
        val template = JdbcTemplate(source)
        template.queryTimeout = 5
        this.jdbc = NamedParameterJdbcTemplate(template)
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true
        reads.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        reads.timeout = 5
        this.transactions = reads
        this.analyzer = analyzer
    }

    // Package-private in Java; Kotlin has no package-private, so this is public on the JVM like the store PR's test constructors.
    internal constructor(
        jdbc: NamedParameterJdbcOperations,
        transactions: TransactionOperations,
        analyzer: KoreanSearchAnalyzer,
    ) {
        this.jdbc = jdbc
        this.transactions = transactions
        this.analyzer = analyzer
    }

    override fun search(request: Search, page: PageRequest): Page {
        return transactions.execute {
            validatePage(page)
            val keyword = text(request.keyword)
            val breed = text(request.breed)
            if (keyword != null) {
                terms(keyword)
            }
            if (breed != null) {
                terms(breed)
            }
            val ordering = ordering(page, keyword != null || breed != null)
            val conditions = Conditions()
            val notice = if (request.noticeNo == null || isBlank(request.noticeNo)) null else javaTrim(request.noticeNo)
            conditions.equal("a.apms_notice_no", "notice", notice)
            conditions.equal("a.species", "species", request.species)
            conditions.equal("a.gender", "gender", request.gender)
            conditions.equal("a.neuter_status", "neuter", request.neuterStatus)
            conditions.equal("a.shelter_id", "shelter", request.shelterId)
            if (request.intakeFrom != null || request.intakeTo != null) {
                val range = IntakeDateRange(request.intakeFrom, request.intakeTo)
                conditions.where.add("a.happen_date BETWEEN :intakeFrom AND :intakeTo")
                conditions.parameters.addValue("intakeFrom", range.from).addValue("intakeTo", range.to)
            }
            if (request.status != null) {
                conditions.equal("a.status", "status", request.status)
            } else if (notice == null) {
                conditions.where.add("a.status IN ('NOTICE','PROTECT')")
            }
            val year = LocalDate.now().year
            if (request.minAge != null) {
                conditions.where.add("a.birth_year<=:maxBirthYear")
                conditions.parameters.addValue("maxBirthYear", year - request.minAge)
            }
            if (request.maxAge != null) {
                conditions.where.add("a.birth_year>=:minBirthYear")
                conditions.parameters.addValue("minBirthYear", year - request.maxAge)
            }
            region(conditions, request.region)
            address(conditions, "city", request.city)
            if (breed != null) {
                addBreed(conditions, breed)
            }
            if (keyword != null) {
                conditions.from = FROM + " LEFT JOIN pawbridge_animal.animal_search_documents ad ON ad.animal_id=a.id " +
                    "LEFT JOIN pawbridge_animal.shelter_search_documents sd ON sd.shelter_id=s.id "
                requirePrepared(conditions)
                addAnalyzedText(conditions, "keyword", keyword)
            }
            page(conditions, page, ordering)
        }!!
    }

    override fun findById(id: Long): Details {
        return transactions.execute {
            jdbc.query(
                "SELECT " + DETAIL_COLUMNS + FROM + "WHERE a.id=:id",
                MapSqlParameterSource("id", id),
                RowMapper { row, index -> detail(row, index) },
            ).firstOrNull() ?: throw AnimalNotFoundException()
        }!!
    }

    override fun findByApmsDesertionNo(number: String?): Details {
        return transactions.execute {
            jdbc.query(
                "SELECT " + DETAIL_COLUMNS + FROM + "WHERE a.apms_desertion_no=:number",
                MapSqlParameterSource("number", number),
                RowMapper { row, index -> detail(row, index) },
            ).firstOrNull() ?: throw AnimalNotFoundException()
        }!!
    }

    override fun findExpiringSoonAnimals(page: PageRequest): Page {
        return transactions.execute {
            validatePage(page)
            val conditions = Conditions()
            conditions.equal("a.status", "status", AnimalStatus.PROTECT)
            conditions.where.add("a.notice_end_date BETWEEN :today AND :deadline")
            val today = LocalDate.now()
            conditions.parameters.addValue("today", today).addValue("deadline", today.plusDays(3))
            page(conditions, page, "a.notice_end_date ASC NULLS LAST,a.id ASC")
        }!!
    }

    private fun page(conditions: Conditions, pageable: PageRequest, ordering: String): Page {
        val where = " WHERE " + (if (conditions.where.isEmpty()) "TRUE" else conditions.where.joinToString(" AND "))
        val total: Long = jdbc.queryForObject(
            "SELECT count(*)" + conditions.from + where,
            conditions.parameters,
            Long::class.javaObjectType,
        )!!
        if (total == 0L || pageable.offset() >= total) {
            return Page.from(listOf(), pageable, total)
        }
        conditions.parameters.addValue("limit", pageable.size()).addValue("offset", pageable.offset())
        val score = if (conditions.scores.isEmpty()) "0" else conditions.scores.joinToString(" + ")
        val content: List<Summary> = jdbc.query(
            "SELECT " + COLUMNS + ",(" + score + ") AS relevance" + conditions.from + where +
                " ORDER BY " + ordering + " LIMIT :limit OFFSET :offset",
            conditions.parameters,
            RowMapper { row, index -> summary(row, index) },
        )
        return Page.from(content, pageable, total)
    }

    /** Keep the source's related dirty-row scope; the separate projector owns integrity audits. */
    private fun requirePrepared(conditions: Conditions) {
        val filters = if (conditions.where.isEmpty()) "TRUE" else conditions.where.joinToString(" AND ")
        val pending = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1" + FROM +
                "WHERE (" + filters + ") AND a.search_dirty) OR EXISTS(SELECT 1 FROM pawbridge_animal.shelters s" +
                " WHERE s.search_dirty AND EXISTS(SELECT 1 FROM pawbridge_animal.animals a" +
                " WHERE a.shelter_id=s.id AND (" + filters + ")))",
            conditions.parameters,
            Boolean::class.javaObjectType,
        )
        if (pending == true) {
            throw SearchProjectionPendingException()
        }
    }

    private fun addAnalyzedText(conditions: Conditions, name: String, value: String) {
        terms(value)
        try {
            val relation = analyzer.queryRelation(value)
            val analyzed: List<String> = if (relation == null) analyzer.tokens(value) else relation.tokens()
            if (analyzed.isEmpty()) {
                conditions.where.add("FALSE")
                return
            }
            val vector = "(ad.tokens || sd.tokens)"
            val query = "plainto_tsquery('simple',:" + name + "Tokens)"
            conditions.parameters.addValue(name + "Tokens", analyzed.joinToString(" "))
            if (relation == null) {
                var tokenIndex = 0
                for (token in java.util.LinkedHashSet(analyzed)) {
                    val parameter = name + "Term" + tokenIndex++
                    conditions.parameters.addValue(parameter, token)
                    val termQuery = "plainto_tsquery('simple',:" + parameter + ")"
                    conditions.where.add(
                        "(numnode(" + termQuery + ")<>1 OR a.id IN (SELECT d.animal_id" +
                            " FROM pawbridge_animal.animal_search_documents d WHERE d.tokens @@ " + termQuery +
                            " UNION ALL SELECT resident.id FROM pawbridge_animal.shelter_search_documents shelter_doc" +
                            " JOIN pawbridge_animal.animals resident ON resident.shelter_id=shelter_doc.shelter_id" +
                            " WHERE shelter_doc.tokens @@ " + termQuery + "))",
                    )
                    if (tokenIndex == 3) {
                        break
                    }
                }
            }
            conditions.where.add(vector + " @@ " + query)
            conditions.scores.add("ts_rank_cd(" + vector + "," + query + ",32)")
            if (relation != null) {
                conditions.parameters.addValue("relation", relation.key())
                conditions.where.add("ad.relation_tokens @@ plainto_tsquery('simple',:relation)")
            }
        } catch (failure: IOException) {
            throw UncheckedIOException(failure)
        }
    }

    private class Conditions {
        var from: String = FROM
        val where: MutableList<String> = ArrayList()
        val scores: MutableList<String> = ArrayList()
        val parameters: MapSqlParameterSource = MapSqlParameterSource()

        fun equal(column: String, key: String, value: Any?) {
            if (value == null) {
                return
            }
            where.add("$column=:$key")
            parameters.addValue(key, if (value is Enum<*>) value.name else value)
        }
    }

    companion object {
        private const val FROM =
            " FROM pawbridge_animal.animals a JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id "
        private const val COLUMNS = "a.id,a.apms_notice_no,a.species,a.breed,a.gender,a.birth_year," +
            "a.special_mark,a.status,a.notice_end_date,a.image_url,a.favorite_count,a.created_at," +
            "s.id AS shelter_id,s.name AS shelter_name"
        private const val DETAIL_COLUMNS = COLUMNS + ",a.neuter_status,a.weight,a.color," +
            "a.notice_start_date,a.happen_place,a.happen_date,a.image_url2,a.description," +
            "a.apms_desertion_no,a.apms_process_state,a.apms_updated_at,a.api_source,a.updated_at"

        // String.split("\\s+") in Java drops trailing empty strings; Pattern.split keeps that behavior.
        private val WHITESPACE: Pattern = Pattern.compile("\\s+")

        // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        // Java String.trim(): strips every leading/trailing char <= U+0020 (Kotlin trim() uses Character.isWhitespace).
        private fun javaTrim(value: String): String = value.trim { it <= ' ' }

        /**
         * 시도 이름마다 찾을 보호소 주소.
         * APMS 보호소 주소는 같은 시도라도 지금 이름, 옛 이름(강원도, 전라북도), 줄인 이름(충남),
         * 2026-07-01 출범한 전남광주통합특별시 이름이 섞여 와서 시도 이름 하나로는 다 찾지 못한다.
         */
        private class RegionAddresses(
            // 주소 어디에든 들어 있으면 맞다. 지역 이름 하나만 찾던 기존 방식과 같다.
            val contained: List<String>,
            // 주소가 이것으로 시작하면 맞다.
            val prefixes: List<String>,
            // 위에서 맞아도 주소가 이것으로 시작하면 뺀다.
            val excludedPrefixes: List<String> = listOf(),
        )

        private const val UNIFIED_JEONNAM_GWANGJU = "전남광주통합특별시"

        // 전남광주통합특별시 주소 가운데 옛 광주광역시에 속하는 구. 나머지 시군은 옛 전라남도다.
        private val GWANGJU_DISTRICTS: List<String> = listOf(
            "$UNIFIED_JEONNAM_GWANGJU 동구",
            "$UNIFIED_JEONNAM_GWANGJU 서구",
            "$UNIFIED_JEONNAM_GWANGJU 남구",
            "$UNIFIED_JEONNAM_GWANGJU 북구",
            "$UNIFIED_JEONNAM_GWANGJU 광산구",
        )

        private val REGION_ADDRESSES: Map<String, RegionAddresses> = mapOf(
            "서울특별시" to RegionAddresses(listOf("서울특별시"), listOf("서울 ")),
            "부산광역시" to RegionAddresses(listOf("부산광역시"), listOf("부산 ")),
            "대구광역시" to RegionAddresses(listOf("대구광역시"), listOf("대구 ")),
            "인천광역시" to RegionAddresses(listOf("인천광역시"), listOf("인천 ")),
            "광주광역시" to RegionAddresses(listOf("광주광역시"), listOf("광주 ") + GWANGJU_DISTRICTS),
            "대전광역시" to RegionAddresses(listOf("대전광역시"), listOf("대전 ")),
            "울산광역시" to RegionAddresses(listOf("울산광역시"), listOf("울산 ")),
            "세종특별자치시" to RegionAddresses(listOf("세종특별자치시"), listOf("세종 ", "세종시 ")),
            "경기도" to RegionAddresses(listOf("경기도"), listOf("경기 ")),
            "강원특별자치도" to RegionAddresses(listOf("강원특별자치도", "강원도"), listOf("강원 ")),
            "충청북도" to RegionAddresses(listOf("충청북도"), listOf("충북 ")),
            "충청남도" to RegionAddresses(listOf("충청남도"), listOf("충남 ")),
            "전북특별자치도" to RegionAddresses(listOf("전북특별자치도", "전라북도"), listOf("전북 ")),
            "전라남도" to RegionAddresses(
                listOf("전라남도"),
                listOf("전남 ", "$UNIFIED_JEONNAM_GWANGJU "),
                GWANGJU_DISTRICTS,
            ),
            UNIFIED_JEONNAM_GWANGJU to RegionAddresses(
                listOf(UNIFIED_JEONNAM_GWANGJU, "광주광역시", "전라남도"),
                listOf("광주 ", "전남 "),
            ),
            "경상북도" to RegionAddresses(listOf("경상북도"), listOf("경북 ")),
            "경상남도" to RegionAddresses(listOf("경상남도"), listOf("경남 ")),
            "제주특별자치도" to RegionAddresses(listOf("제주특별자치도"), listOf("제주 ", "제주도 ")),
        )

        /** 표에 있는 시도 이름이면 그 시도의 여러 주소 표기를 모두 찾고, 아니면 예전처럼 주소에 든 글자로 찾는다. */
        private fun region(conditions: Conditions, value: String?) {
            val normalized = text(value) ?: return
            val addresses = REGION_ADDRESSES[normalized]
            if (addresses == null) {
                address(conditions, "region", value)
                return
            }
            val matches = ArrayList<String>()
            addresses.contained.forEachIndexed { index, word ->
                val key = "regionContained$index"
                matches.add("lower(s.address) LIKE :$key ESCAPE '!'")
                conditions.parameters.addValue(key, like(word))
            }
            addresses.prefixes.forEachIndexed { index, prefix ->
                val key = "regionPrefix$index"
                matches.add("lower(s.address) LIKE :$key ESCAPE '!'")
                conditions.parameters.addValue(key, startsWith(prefix))
            }
            var condition = "(" + matches.joinToString(" OR ") + ")"
            if (addresses.excludedPrefixes.isNotEmpty()) {
                val excluded = ArrayList<String>()
                addresses.excludedPrefixes.forEachIndexed { index, prefix ->
                    val key = "regionExcluded$index"
                    excluded.add("lower(s.address) LIKE :$key ESCAPE '!'")
                    conditions.parameters.addValue(key, startsWith(prefix))
                }
                condition += " AND NOT (" + excluded.joinToString(" OR ") + ")"
            }
            conditions.where.add("($condition)")
        }

        private fun address(conditions: Conditions, name: String, value: String?) {
            val normalized = text(value) ?: return
            val terms = terms(normalized)
            for (i in terms.indices) {
                val key = name + i
                conditions.where.add("lower(s.address) LIKE :$key ESCAPE '!'")
                conditions.parameters.addValue(key, like(terms[i]))
            }
        }

        private fun addBreed(conditions: Conditions, value: String) {
            conditions.parameters.addValue("breedPart", like(value)).addValue("breedExact", value)
            conditions.where.add("lower(a.breed) LIKE :breedPart ESCAPE '!'")
            conditions.scores.add("CASE WHEN lower(a.breed)=:breedExact THEN 100 ELSE 0 END")
        }

        private fun text(value: String?): String? {
            if (value == null || isBlank(value)) {
                return null
            }
            val normalized = javaTrim(value).lowercase(Locale.ROOT)
            if (normalized.length > 200) {
                throw IllegalArgumentException("검색어는 200자 이하여야 합니다.")
            }
            return normalized
        }

        private fun terms(value: String): Array<String> {
            val terms = WHITESPACE.split(value)
            if (terms.size > 8) {
                throw IllegalArgumentException("검색어는 8개 단어 이하여야 합니다.")
            }
            return terms
        }

        private fun like(value: String): String {
            return "%" + escapeLike(value) + "%"
        }

        private fun startsWith(value: String): String {
            return escapeLike(value) + "%"
        }

        private fun escapeLike(value: String): String {
            return value.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        }

        private fun validatePage(pageable: PageRequest) {
            if (!pageable.paged() || pageable.size() < 1 || pageable.size() > 100 || pageable.number() < 0) {
                throw IllegalArgumentException("페이지 크기는 1 이상 100 이하여야 합니다.")
            }
            if (pageable.orders().size > 1) {
                throw IllegalArgumentException("정렬 기준은 하나만 지정할 수 있습니다.")
            }
            if (pageable.offset() + pageable.size() > 10_000) {
                throw IllegalArgumentException("검색 결과는 최대 10,000건까지만 조회할 수 있습니다. 검색 조건을 좁혀주세요.")
            }
        }

        private fun ordering(pageable: PageRequest, hasText: Boolean): String {
            val order = if (pageable.orders().isEmpty()) Order("noticeEndDate", true) else pageable.orders().first()
            // The Java original called property.equals(...) and switch(property), which throw NPE for null.
            val property = order.property!!
            if (property == "relevance") {
                if (!hasText) {
                    throw IllegalArgumentException("관련도순 정렬에는 키워드나 품종이 필요합니다.")
                }
                return "relevance DESC,a.created_at DESC NULLS LAST,a.id ASC"
            }
            val column = when (property) {
                "createdAt", "created_at" -> "a.created_at"
                "happenDate", "happen_date" -> "a.happen_date"
                "updatedAt", "updated_at" -> "a.updated_at"
                "noticeEndDate", "notice_end_date" -> "a.notice_end_date"
                "birthYear", "birth_year", "age" -> "a.birth_year"
                "apmsDesertionNo", "apms_desertion_no" -> "a.apms_desertion_no"
                "favoriteCount", "favorite_count" -> "a.favorite_count"
                else -> throw IllegalArgumentException("지원하지 않는 정렬 기준입니다: $property")
            }
            var ascending = order.ascending
            if (property == "age") {
                ascending = !ascending
            }
            return column + (if (ascending) " ASC" else " DESC") + " NULLS LAST,a.id ASC"
        }

        @JvmStatic
        @Throws(SQLException::class)
        @Suppress("UNUSED_PARAMETER")
        fun summary(row: ResultSet, index: Int): Summary {
            val year = row.getObject("birth_year", Int::class.javaObjectType)
            return Summary(
                row.getLong("id"),
                row.getString("apms_notice_no"),
                Species.valueOf(row.getString("species")),
                row.getString("breed"),
                Gender.valueOf(row.getString("gender")),
                year,
                age(year),
                row.getString("special_mark"),
                AnimalStatus.valueOf(row.getString("status")),
                row.getObject("notice_end_date", LocalDate::class.java),
                row.getString("image_url"),
                row.getObject("favorite_count", Int::class.javaObjectType),
                row.getLong("shelter_id"),
                row.getString("shelter_name"),
                row.getObject("created_at", LocalDateTime::class.java),
            )
        }

        @JvmStatic
        @Throws(SQLException::class)
        @Suppress("UNUSED_PARAMETER")
        fun detail(row: ResultSet, index: Int): Details {
            val year = row.getObject("birth_year", Int::class.javaObjectType)
            return Details(
                row.getLong("id"),
                row.getString("apms_notice_no"),
                Species.valueOf(row.getString("species")),
                row.getString("breed"),
                Gender.valueOf(row.getString("gender")),
                NeuterStatus.valueOf(row.getString("neuter_status")),
                year,
                age(year),
                row.getString("weight"),
                row.getString("color"),
                row.getString("special_mark"),
                AnimalStatus.valueOf(row.getString("status")),
                row.getObject("notice_start_date", LocalDate::class.java),
                row.getObject("notice_end_date", LocalDate::class.java),
                row.getString("happen_place"),
                row.getObject("happen_date", LocalDate::class.java),
                row.getString("image_url"),
                row.getString("image_url2"),
                row.getString("description"),
                row.getObject("favorite_count", Int::class.javaObjectType),
                row.getLong("shelter_id"),
                row.getString("shelter_name"),
                row.getString("apms_desertion_no"),
                row.getString("apms_process_state"),
                row.getObject("apms_updated_at", LocalDateTime::class.java),
                ApiSource.valueOf(row.getString("api_source")),
                row.getObject("created_at", LocalDateTime::class.java),
                row.getObject("updated_at", LocalDateTime::class.java),
            )
        }

        private fun age(year: Int?): Int? = if (year == null) null else LocalDate.now().year - year
    }
}
