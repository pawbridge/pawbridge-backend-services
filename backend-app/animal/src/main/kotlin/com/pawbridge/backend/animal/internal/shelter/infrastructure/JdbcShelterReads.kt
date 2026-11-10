package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.pawbridge.backend.animal.internal.discovery.application.IntakeDateRange
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
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.regex.Pattern
import javax.sql.DataSource

/** Selected original read SQL and JPA scalar queries; no writes or background work. */
class JdbcShelterReads : ShelterReads {
    private val jdbc: NamedParameterJdbcOperations
    private val discoveryJdbc: NamedParameterJdbcOperations
    private val reads: TransactionOperations
    private val discoveryReads: TransactionOperations
    private val mapper: ObjectMapper

    constructor(source: DataSource, manager: PlatformTransactionManager, mapper: ObjectMapper) {
        this.jdbc = NamedParameterJdbcTemplate(JdbcTemplate(source))
        val discoveryTemplate = JdbcTemplate(source)
        discoveryTemplate.queryTimeout = 5
        this.discoveryJdbc = NamedParameterJdbcTemplate(discoveryTemplate)
        val regular = TransactionTemplate(manager)
        regular.isReadOnly = true
        this.reads = regular
        val discovery = TransactionTemplate(manager)
        discovery.isReadOnly = true
        discovery.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        discovery.timeout = 5
        this.discoveryReads = discovery
        this.mapper = mapper
    }

    // Package-private in Java; public on the JVM here because Kotlin has no package-private.
    internal constructor(
        jdbc: NamedParameterJdbcOperations,
        discoveryJdbc: NamedParameterJdbcOperations,
        reads: TransactionOperations,
        discoveryReads: TransactionOperations,
        mapper: ObjectMapper,
    ) {
        this.jdbc = jdbc
        this.discoveryJdbc = discoveryJdbc
        this.reads = reads
        this.discoveryReads = discoveryReads
        this.mapper = mapper
    }

    override fun search(search: Search, page: PageRequest): Page<Summary> {
        return reads.execute {
            if (page.offset() > Integer.MAX_VALUE) {
                throw InvalidDataAccessApiUsageException("Page offset exceeds Integer.MAX_VALUE (2147483647)")
            }
            val params = MapSqlParameterSource().addValue("limit", page.size()).addValue("offset", page.offset())
            val where = filter(search, params)
            val sorting = ordering(page.orders(), search.keyword != null)
            var rows: List<Summary> = jdbc.query(
                "SELECT " + SUMMARY + FROM + sorting.joins + where + sorting.orderBy + " LIMIT :limit OFFSET :offset",
                params,
                RowMapper { row, index -> summary(row, index) },
            )
            if (sorting.collectionJoin) {
                // Hibernate6 ALLOW semantics deduplicate entity roots after SQL LIMIT/OFFSET.
                val roots = LinkedHashMap<Long?, Summary>()
                rows.forEach { row -> roots.putIfAbsent(row.id, row) }
                rows = java.util.List.copyOf(roots.values)
            }
            // Match PageableExecutionUtils: no count for an inferable partial page.
            val total: Long
            if (rows.size < page.size() && (page.number() == 0 || rows.isNotEmpty())) {
                total = page.offset() + rows.size
            } else {
                total = jdbc.queryForObject("SELECT count(*)" + FROM + where, params, Long::class.javaObjectType)!!
            }
            Page(rows, total)
        }!!
    }

    override fun findById(id: Long): Details {
        return detail("s.id=:value", id)
    }

    override fun findByCareRegNo(careRegNo: String?): Details {
        return detail("s.care_reg_no=:value", careRegNo)
    }

    private fun detail(predicate: String, value: Any?): Details {
        return reads.execute {
            val shelter = jdbc.query(
                "SELECT " + DETAILS + FROM + "WHERE " + predicate,
                MapSqlParameterSource("value", value),
                RowMapper { row, index -> details(row, index) },
            ).firstOrNull()
                // 없는 보호소는 404로 답한다. 500이면 화면이 일시 오류로 보고 검색 제외(noindex)를 넣지 못한다.
                // 다른 모듈의 조회(ModuleAnimalShelterReads)는 RuntimeException을 그대로 이용 불가로 감싸므로 영향이 없다.
                ?: throw ShelterNotFoundException()
            val information = jdbc.query(
                "SELECT details,collected_at FROM pawbridge_animal.shelter_public_information WHERE shelter_id=:id",
                MapSqlParameterSource("id", shelter.id),
                RowMapper { row, _ -> publicInformation(row) },
            ).firstOrNull()
            Details(
                shelter.id,
                shelter.careRegNo,
                shelter.name,
                shelter.phone,
                shelter.address,
                shelter.ownerName,
                shelter.organizationName,
                shelter.email,
                shelter.introduction,
                shelter.adoptionProcedure,
                shelter.operatingHours,
                shelter.createdAt,
                shelter.updatedAt,
                information,
            )
        }!!
    }

    private fun publicInformation(row: ResultSet): PublicInformation {
        try {
            val node = mapper.readTree(row.getString("details")) as ObjectNode
            node.put("source", "APMS_SHELTER_INFO")
            node.put("collectedAt", row.getTimestamp("collected_at").toLocalDateTime().toString())
            return mapper.treeToValue(node, PublicInformation::class.java)
        } catch (failure: Exception) {
            throw IllegalStateException("Invalid shelter public information")
        }
    }

    override fun count(): Long {
        return reads.execute {
            jdbc.queryForObject(
                "SELECT count(*) FROM pawbridge_animal.shelters",
                MapSqlParameterSource(),
                Long::class.javaObjectType,
            )
        }!!
    }

    override fun discover(
        keyword: String?,
        address: String?,
        from: LocalDate?,
        to: LocalDate?,
        page: Int,
        size: Int,
    ): Page<Discovery> {
        return discoveryReads.execute {
            val range = IntakeDateRange(from, to)
            if (page < 0 || size < 1 || size > 24 || (page.toLong() + 1) * size > 10_000) {
                throw IllegalArgumentException("보호소는 페이지당 최대 24개, 결과 10,000개 이내로 조회해주세요.")
            }
            val params = MapSqlParameterSource().addValue("from", range.from).addValue("to", range.to)
                .addValue("keyword", like(keyword)).addValue("address", like(address))
                .addValue("limit", size).addValue("offset", page.toLong() * size)
            val matching = """
                FROM pawbridge_animal.shelters s JOIN pawbridge_animal.animals a ON a.shelter_id = s.id
                WHERE a.status = 'PROTECT' AND a.happen_date BETWEEN :from AND :to
                  AND (s.name ILIKE :keyword ESCAPE '!' OR s.address ILIKE :keyword ESCAPE '!')
                  AND COALESCE(s.address, '') ILIKE :address ESCAPE '!'
                """.trimIndent() + "\n"
            val total: Long = discoveryJdbc.queryForObject(
                "SELECT COUNT(DISTINCT s.id) " + matching,
                params,
                Long::class.javaObjectType,
            )!!
            val shelters: List<Discovery> = discoveryJdbc.query(
                """
                SELECT s.id, s.care_reg_no, s.name, s.address, s.phone, COUNT(*) AS protected_count
                """.trimIndent() + "\n" + matching + """
                GROUP BY s.id, s.care_reg_no, s.name, s.address, s.phone
                ORDER BY protected_count DESC, s.name ASC, s.id ASC LIMIT :limit OFFSET :offset
                """.trimIndent() + "\n",
                params,
                RowMapper { row, _ ->
                    Discovery(
                        row.getLong("id"),
                        row.getString("care_reg_no"),
                        row.getString("name"),
                        row.getString("address"),
                        row.getString("phone"),
                        row.getLong("protected_count"),
                        java.util.List.of(),
                    )
                },
            )
            if (shelters.isEmpty()) {
                return@execute Page(java.util.List.of<Discovery>(), total)
            }
            params.addValue("ids", shelters.map { shelter -> shelter.id })
            val previews = HashMap<Long, MutableList<Preview>>()
            discoveryJdbc.query(
                """
                SELECT * FROM (
                  SELECT id, shelter_id, breed, species, gender, birth_year, image_url, happen_date,
                         ROW_NUMBER() OVER (PARTITION BY shelter_id ORDER BY happen_date DESC, id ASC) AS rn
                  FROM pawbridge_animal.animals WHERE shelter_id IN (:ids) AND status = 'PROTECT'
                    AND happen_date BETWEEN :from AND :to
                ) ranked WHERE rn <= 3 ORDER BY shelter_id, rn
                """.trimIndent() + "\n",
                params,
                RowCallbackHandler { row ->
                    previews.computeIfAbsent(row.getLong("shelter_id")) { ArrayList() }.add(
                        Preview(
                            row.getLong("id"),
                            row.getString("breed"),
                            row.getString("species"),
                            row.getString("gender"),
                            row.getObject("birth_year", Int::class.javaObjectType),
                            row.getString("image_url"),
                            row.getObject("happen_date", LocalDate::class.java),
                        ),
                    )
                },
            )
            Page(
                shelters.map { s ->
                    Discovery(
                        s.id,
                        s.careRegNo,
                        s.name,
                        s.address,
                        s.phone,
                        s.protectedCount,
                        java.util.List.copyOf(previews.getOrDefault(s.id, java.util.List.of())),
                    )
                },
                total,
            )
        }!!
    }

    override fun observations(shelterId: Long, from: LocalDate?, to: LocalDate?): List<Observation> {
        return discoveryReads.execute {
            IntakeDateRange(from, to)
            val params = MapSqlParameterSource("id", shelterId).addValue("from", from).addValue("to", to)
            val exists = discoveryJdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pawbridge_animal.shelters WHERE id=:id)",
                params,
                Boolean::class.javaObjectType,
            )
            if (exists != true) {
                throw ShelterNotFoundException()
            }
            discoveryJdbc.query(
                """
                SELECT observation_date, observed_at, protected_count FROM pawbridge_animal.shelter_daily_observations
                WHERE shelter_id = :id AND observation_date BETWEEN :from AND :to ORDER BY observation_date
                """.trimIndent() + "\n",
                params,
                RowMapper { row, _ ->
                    Observation(
                        row.getObject("observation_date", LocalDate::class.java),
                        row.getObject("observed_at", OffsetDateTime::class.java),
                        row.getLong("protected_count"),
                    )
                },
            )
        }!!
    }

    private class Sorting(
        val joins: String,
        val orderBy: String,
        val collectionJoin: Boolean,
    )

    private companion object {
        private const val SUMMARY = "s.id,s.care_reg_no,s.name,s.phone,s.address,s.organization_name,s.created_at"
        private const val DETAILS = SUMMARY +
            ",s.owner_name,s.email,s.introduction,s.adoption_procedure,s.operating_hours,s.updated_at"
        private const val FROM = " FROM pawbridge_animal.shelters s "

        // Map.ofEntries keeps the original null rejection on lookups.
        private val SORTS: Map<String, String> = java.util.Map.ofEntries(
            java.util.Map.entry("id", "id"),
            java.util.Map.entry("careRegNo", "care_reg_no"),
            java.util.Map.entry("name", "name"),
            java.util.Map.entry("phone", "phone"),
            java.util.Map.entry("address", "address"),
            java.util.Map.entry("ownerName", "owner_name"),
            java.util.Map.entry("organizationName", "organization_name"),
            java.util.Map.entry("email", "email"),
            java.util.Map.entry("introduction", "introduction"),
            java.util.Map.entry("adoptionProcedure", "adoption_procedure"),
            java.util.Map.entry("operatingHours", "operating_hours"),
            java.util.Map.entry("createdAt", "created_at"),
            java.util.Map.entry("updatedAt", "updated_at"),
        )
        private val ANIMAL_SORTS: Map<String, String> = java.util.Map.ofEntries(
            java.util.Map.entry("id", "id"),
            java.util.Map.entry("apmsDesertionNo", "apms_desertion_no"),
            java.util.Map.entry("apmsNoticeNo", "apms_notice_no"),
            java.util.Map.entry("species", "species"),
            java.util.Map.entry("breed", "breed"),
            java.util.Map.entry("birthYear", "birth_year"),
            java.util.Map.entry("weight", "weight"),
            java.util.Map.entry("color", "color"),
            java.util.Map.entry("gender", "gender"),
            java.util.Map.entry("neuterStatus", "neuter_status"),
            java.util.Map.entry("specialMark", "special_mark"),
            java.util.Map.entry("apmsProcessState", "apms_process_state"),
            java.util.Map.entry("noticeStartDate", "notice_start_date"),
            java.util.Map.entry("noticeEndDate", "notice_end_date"),
            java.util.Map.entry("apmsUpdatedAt", "apms_updated_at"),
            java.util.Map.entry("happenDate", "happen_date"),
            java.util.Map.entry("happenPlace", "happen_place"),
            java.util.Map.entry("imageUrl", "image_url"),
            java.util.Map.entry("imageUrl2", "image_url2"),
            java.util.Map.entry("status", "status"),
            java.util.Map.entry("apiSource", "api_source"),
            java.util.Map.entry("favoriteCount", "favorite_count"),
            java.util.Map.entry("description", "description"),
            java.util.Map.entry("createdAt", "created_at"),
            java.util.Map.entry("updatedAt", "updated_at"),
        )
        private val ANIMAL_TEXT: List<String> = java.util.List.of(
            "apmsDesertionNo", "apmsNoticeNo", "breed", "weight", "color", "specialMark",
            "apmsProcessState", "happenPlace", "imageUrl", "imageUrl2", "description",
        )
        private val NON_TEXT_SHELTER_SORTS: List<String> = java.util.List.of("id", "createdAt", "updatedAt")
        private val COLLECTION_PATHS: List<String> = java.util.List.of("animals", "shelter")

        // String.split(regex, -1) in Java keeps trailing empty strings; Pattern.split(input, -1) is the same call.
        private val DOT: Pattern = Pattern.compile("\\.")
        private val DOT_OR_UNDERSCORE: Pattern = Pattern.compile("[._]")

        // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        // Java String.trim(): strips every leading/trailing char <= U+0020 (Kotlin trim() uses Character.isWhitespace).
        private fun javaTrim(value: String): String = value.trim { it <= ' ' }

        private fun hasText(value: String?): Boolean = value != null && !isBlank(value)

        private fun filter(search: Search, params: MapSqlParameterSource): String {
            if (hasText(search.keyword) && hasText(search.address)) {
                params.addValue("keyword", "%" + javaTrim(search.keyword!!) + "%")
                    .addValue("address", "%" + javaTrim(search.address!!) + "%")
                return "WHERE s.address LIKE :address ESCAPE '' AND " +
                    "(s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE '')"
            }
            if (search.keyword != null) {
                params.addValue("keyword", "%" + search.keyword + "%")
                // Hibernate PostgreSQL's JPQL LIKE emits ESCAPE '' when no explicit escape is set.
                return "WHERE s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE ''"
            }
            if (search.name != null) {
                return containing("name", search.name, params)
            }
            if (search.address != null) {
                return containing("address", search.address, params)
            }
            if (search.organizationName != null) {
                return containing("organization_name", search.organizationName, params)
            }
            return ""
        }

        private fun containing(column: String, value: String, params: MapSqlParameterSource): String {
            // Spring Data derived Containing escapes the default backslash, '%' and '_'.
            params.addValue("term", "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            return "WHERE s.$column LIKE :term ESCAPE '\\'"
        }

        private fun ordering(orders: List<Order>, declaredQuery: Boolean): Sorting {
            val terms = ArrayList<String>()
            val joins = StringBuilder()
            val aliases = LinkedHashMap<String, String>()
            for (order in orders) {
                val property = if (declaredQuery && order.property!!.startsWith("s.")) {
                    order.property.substring(2)
                } else {
                    order.property!!
                }
                val path: Array<String> = if (declaredQuery) DOT.split(property, -1) else derivedSortPath(property)
                var alias = "s"
                var prefix = ""
                var animal = false
                for (index in 0 until path.size - 1) {
                    var relation = path[index]
                    if (!declaredQuery && relation.isNotEmpty()) {
                        relation = Character.toLowerCase(relation[0]) + relation.substring(1)
                    }
                    if ((!animal && relation != "animals") || (animal && relation != "shelter")) {
                        throw IllegalStateException("Unknown shelter sort property")
                    }
                    if (declaredQuery) {
                        throw InvalidDataAccessApiUsageException("Collection paths require the source derived-query sort")
                    }
                    prefix += ".$relation"
                    var joined = aliases[prefix]
                    if (joined == null) {
                        joined = "sort_" + aliases.size
                        aliases[prefix] = joined
                        joins.append(if (animal) " LEFT JOIN pawbridge_animal.shelters " else " LEFT JOIN pawbridge_animal.animals ")
                            .append(joined).append(" ON ").append(joined).append(if (animal) ".id=" else ".shelter_id=")
                            .append(alias).append(if (animal) ".shelter_id " else ".id ")
                    }
                    alias = joined
                    animal = !animal
                }
                var leaf = path[path.size - 1]
                if (!declaredQuery && leaf.isNotEmpty()) {
                    leaf = Character.toLowerCase(leaf[0]) + leaf.substring(1)
                }
                val column = (if (animal) ANIMAL_SORTS else SORTS)[leaf]
                if (column == null) {
                    throw IllegalStateException("Unknown shelter sort property")
                }
                var expression = "$alias.$column"
                val text = if (animal) ANIMAL_TEXT.contains(leaf) else !NON_TEXT_SHELTER_SORTS.contains(leaf)
                if (order.ignoreCase && (text || declaredQuery)) {
                    expression = "lower($expression)"
                }
                terms.add(expression + (if (order.ascending) " ASC" else " DESC"))
            }
            // No synthetic ID tie-break or null ordering: the source JPA scalar sort has neither.
            return Sorting(
                joins.toString(),
                if (terms.isEmpty()) "" else " ORDER BY " + terms.joinToString(","),
                aliases.isNotEmpty(),
            )
        }

        private fun derivedSortPath(property: String): Array<String> {
            // Source PropertyPath supports dot/underscore and camel paths across these two entities.
            val path = ArrayList<String>()
            var animal = false
            for (part in DOT_OR_UNDERSCORE.split(property, -1)) {
                var remaining = part
                while (remaining.isNotEmpty()) {
                    val value = Character.toLowerCase(remaining[0]) + remaining.substring(1)
                    if ((if (animal) ANIMAL_SORTS else SORTS).containsKey(value)) {
                        path.add(value)
                        remaining = ""
                    } else {
                        val relation = if (animal) "shelter" else "animals"
                        if (!value.startsWith(relation) ||
                            (value.length > relation.length && !Character.isUpperCase(value[relation.length]))
                        ) {
                            throw IllegalStateException("Unknown shelter sort property")
                        }
                        path.add(relation)
                        animal = !animal
                        remaining = value.substring(relation.length)
                    }
                }
                if (part.isEmpty()) {
                    throw IllegalStateException("Unknown shelter sort property")
                }
            }
            if (path.isNotEmpty() && COLLECTION_PATHS.contains(path.last())) {
                path.add("id")
            }
            return path.toTypedArray()
        }

        private fun like(text: String?): String {
            val value = if (text == null) "" else javaTrim(text)
            if (value.length > 100) {
                throw IllegalArgumentException("검색어는 100자 이내로 입력해주세요.")
            }
            return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        }

        @Throws(SQLException::class)
        @Suppress("UNUSED_PARAMETER")
        private fun summary(row: ResultSet, index: Int): Summary {
            return Summary(
                row.getObject("id", Long::class.javaObjectType),
                row.getString("care_reg_no"),
                row.getString("name"),
                row.getString("phone"),
                row.getString("address"),
                row.getString("organization_name"),
                row.getObject("created_at", LocalDateTime::class.java),
            )
        }

        @Throws(SQLException::class)
        @Suppress("UNUSED_PARAMETER")
        private fun details(row: ResultSet, index: Int): Details {
            val s = summary(row, index)
            return Details(
                s.id,
                s.careRegNo,
                s.name,
                s.phone,
                s.address,
                row.getString("owner_name"),
                s.organizationName,
                row.getString("email"),
                row.getString("introduction"),
                row.getString("adoption_procedure"),
                row.getString("operating_hours"),
                s.createdAt,
                row.getObject("updated_at", LocalDateTime::class.java),
                null,
            )
        }
    }
}
