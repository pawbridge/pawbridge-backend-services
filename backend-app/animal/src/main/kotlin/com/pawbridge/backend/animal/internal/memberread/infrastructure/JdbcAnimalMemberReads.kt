package com.pawbridge.backend.animal.internal.memberread.infrastructure

import com.pawbridge.backend.animal.api.AnimalMemberReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.animal.api.AnimalMemberReads.Animal
import com.pawbridge.backend.animal.api.AnimalMemberReads.Page
import com.pawbridge.backend.animal.api.AnimalMemberReads.PageRequest
import com.pawbridge.backend.animal.api.AnimalMemberReads.StatusChange
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.api.AnimalShelterReads.Shelter
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.ApiSource
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.NeuterStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import org.springframework.data.mapping.PropertyPath
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.regex.Pattern
import javax.sql.DataSource

/** Only the two selected MyPageAnimalServiceImpl JPA reads, translated to the existing PG schema. */
class JdbcAnimalMemberReads : AnimalMemberReads {
    private val jdbc: NamedParameterJdbcOperations
    private val reads: TransactionOperations
    private val shelters: AnimalShelterReads

    constructor(source: DataSource, manager: PlatformTransactionManager, shelters: AnimalShelterReads) :
        this(NamedParameterJdbcTemplate(source), memberRead(manager), shelters)

    // Package-private in Java; public on the JVM here because Kotlin has no package-private.
    internal constructor(
        jdbc: NamedParameterJdbcOperations,
        reads: TransactionOperations,
        shelters: AnimalShelterReads,
    ) {
        this.jdbc = jdbc
        this.reads = reads
        this.shelters = shelters
    }

    override fun findByCareRegNo(careRegNo: String?): Shelter? {
        return shelters.findByCareRegNo(careRegNo)
    }

    override fun batch(ids: List<Long?>?): List<Animal> {
        if (ids == null || ids.isEmpty()) {
            return ArrayList()
        }
        try {
            return reads.execute {
                jdbc.query(
                    "SELECT " + COLUMNS + FROM + " WHERE a.id IN (:ids)",
                    MapSqlParameterSource("ids", ArrayList(ids)),
                    RowMapper { row, index -> animal(row, index) },
                )
            }!!
        } catch (failure: RuntimeException) {
            throw AnimalMemberReadUnavailableException(failure)
        }
    }

    override fun statusChanges(ids: List<Long>, since: Instant): List<StatusChange> {
        if (ids.isEmpty()) {
            return listOf()
        }
        try {
            return reads.execute {
                jdbc.query(
                    "SELECT id,status,previous_status,status_changed_at FROM pawbridge_animal.animals" +
                        " WHERE id IN (:ids) AND status_changed_at >= :since",
                    MapSqlParameterSource("ids", ids).addValue("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC)),
                    RowMapper { row, _ ->
                        StatusChange(
                            row.getLong("id"),
                            row.getString("status"),
                            row.getString("previous_status"),
                            row.getObject("status_changed_at", OffsetDateTime::class.java).toInstant(),
                        )
                    },
                )
            }!!
        } catch (failure: RuntimeException) {
            throw AnimalMemberReadUnavailableException(failure)
        }
    }

    override fun byShelter(shelterId: Long?, request: PageRequest?): Page {
        try {
            return reads.execute {
                // A null request throws NPE inside the transaction callback, so it is wrapped like in Java.
                val checked = request!!
                if (checked.page < 0 || checked.size < 1) {
                    throw IllegalArgumentException("Invalid effective pageable")
                }
                val offset = checked.page.toLong() * checked.size
                if (offset > Integer.MAX_VALUE) {
                    throw IllegalArgumentException("Page offset exceeds Integer.MAX_VALUE (2147483647)")
                }
                val sorting = sorting(checked.sort)
                val parameters = MapSqlParameterSource("shelterId", shelterId)
                    .addValue("limit", checked.size).addValue("offset", offset)
                var content: List<Animal> = jdbc.query(
                    "SELECT " + COLUMNS + FROM + sorting.joins +
                        " WHERE a.shelter_id=:shelterId AND a.api_source='MANUAL'" + sorting.orderBy +
                        " LIMIT :limit OFFSET :offset",
                    parameters,
                    RowMapper { row, index -> animal(row, index) },
                )
                if (sorting.collectionJoin) {
                    // Hibernate6 root identity deduplication happens AFTER SQL LIMIT/OFFSET.
                    val roots = LinkedHashMap<Long?, Animal>()
                    content.forEach { animal -> roots.putIfAbsent(animal.id, animal) }
                    content = ArrayList(roots.values)
                }
                var total: Long
                // PageableExecutionUtils skips count on a partial nonempty page, or any partial first page.
                if (content.size < checked.size && (checked.page == 0 || content.isNotEmpty())) {
                    total = offset + content.size
                } else {
                    total = jdbc.queryForObject(
                        "SELECT count(*) FROM pawbridge_animal.animals a" +
                            " WHERE a.shelter_id=:shelterId AND a.api_source='MANUAL'",
                        parameters,
                        Long::class.javaObjectType,
                    )!!
                }
                // PageImpl adapts stale count only for a nonempty final page.
                if (content.isNotEmpty() && offset + checked.size > total) {
                    total = offset + content.size
                }
                val totalPages = Math.ceil(total.toDouble() / checked.size).toInt()
                Page(content, checked.page, checked.size, total, totalPages, checked.page + 1 >= totalPages)
            }!!
        } catch (failure: RuntimeException) {
            throw AnimalMemberReadUnavailableException(failure)
        }
    }

    private class Sorting(
        val joins: String,
        val orderBy: String,
        val collectionJoin: Boolean,
    )

    // Only the two selected source entities' persistent property shapes, for the existing
    // Spring Data PropertyPath grammar (case, camel, underscore, quoting and depth semantics).
    // These are private metadata, never instantiated, serialized, persisted or exposed as module API.
    // Spring Data resolves the property types from the fields, so every field keeps its Java type.
    private class AnimalSortProperties(
        private val id: Long?,
        private val apmsDesertionNo: String?,
        private val apmsNoticeNo: String?,
        private val species: Species?,
        private val breed: String?,
        private val birthYear: Int?,
        private val weight: String?,
        private val color: String?,
        private val gender: Gender?,
        private val neuterStatus: NeuterStatus?,
        private val specialMark: String?,
        private val apmsProcessState: String?,
        private val noticeStartDate: LocalDate?,
        private val noticeEndDate: LocalDate?,
        private val apmsUpdatedAt: LocalDateTime?,
        private val happenDate: LocalDate?,
        private val happenPlace: String?,
        private val imageUrl: String?,
        private val imageUrl2: String?,
        private val shelter: ShelterSortProperties?,
        private val status: AnimalStatus?,
        private val apiSource: ApiSource?,
        private val favoriteCount: Int?,
        private val description: String?,
        private val createdAt: LocalDateTime?,
        private val updatedAt: LocalDateTime?,
    )

    private class ShelterSortProperties(
        private val id: Long?,
        private val careRegNo: String?,
        private val name: String?,
        private val phone: String?,
        private val address: String?,
        private val ownerName: String?,
        private val organizationName: String?,
        private val email: String?,
        private val introduction: String?,
        private val adoptionProcedure: String?,
        private val operatingHours: String?,
        private val createdAt: LocalDateTime?,
        private val updatedAt: LocalDateTime?,
        private val animals: List<AnimalSortProperties>?,
    )

    companion object {
        private const val FROM =
            " FROM pawbridge_animal.animals a LEFT JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id"
        private const val COLUMNS = "a.id,a.apms_notice_no,a.species,a.breed,a.gender,a.birth_year,a.special_mark," +
            "a.status,a.notice_end_date,a.image_url,a.favorite_count,a.created_at,s.id AS shelter_id,s.name AS shelter_name"

        // Map.ofEntries keeps the original null rejection on lookups.
        private val ANIMAL_COLUMNS: Map<String, String> = java.util.Map.ofEntries(
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
        private val SHELTER_COLUMNS: Map<String, String> = java.util.Map.ofEntries(
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

        // String.split(",") and split("\\.") in Java drop trailing empty strings; Pattern.split keeps that behavior.
        private val COMMA: Pattern = Pattern.compile(",")
        private val DOT: Pattern = Pattern.compile("\\.")

        // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        private fun memberRead(manager: PlatformTransactionManager): TransactionTemplate {
            val transaction = TransactionTemplate(manager)
            transaction.isReadOnly = true
            transaction.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            return transaction
        }

        /** The remote resolver parses ONE flattened sort value; it does not parse alternating pairs. */
        @JvmStatic
        fun ordering(wireSort: String?): String = sorting(wireSort).orderBy

        private fun sorting(wireSort: String?): Sorting {
            if (wireSort == null || wireSort.isEmpty()) {
                return Sorting("", " ORDER BY a.created_at DESC", false)
            }
            val parts: MutableList<String> = ArrayList(
                COMMA.split(wireSort).filter { part -> !part.all { it == '.' } },
            )
            val ignoreCase = parts.isNotEmpty() && parts.last().equals("ignorecase", ignoreCase = true)
            if (ignoreCase) {
                parts.removeAt(parts.lastIndex)
            }
            var direction = "ASC"
            if (parts.isNotEmpty() &&
                (parts.last().equals("asc", ignoreCase = true) || parts.last().equals("desc", ignoreCase = true))
            ) {
                direction = if (parts.removeAt(parts.lastIndex).equals("desc", ignoreCase = true)) "DESC" else "ASC"
            }
            val orders = ArrayList<String>()
            val joins = StringBuilder()
            val aliases = LinkedHashMap<String, String>()
            aliases["shelter"] = "s" // The DTO projection already joins this exact association.
            var collectionJoin = false
            for (property in parts) {
                if (isBlank(property)) {
                    continue
                }
                val parsed = PropertyPath.from(property, AnimalSortProperties::class.java)
                val path: MutableList<String> = DOT.split(parsed.toDotPath()).toMutableList()
                val leafType: Class<*> = parsed.leafProperty.type
                // Criteria ordering by an entity association orders its primary key.
                if (leafType == AnimalSortProperties::class.java || leafType == ShelterSortProperties::class.java) {
                    path.add("id")
                }
                var alias = "a"
                var prefix = ""
                var animal = true
                for (index in 0 until path.size - 1) {
                    val relation = path[index]
                    if ((if (animal) "shelter" else "animals") != relation) {
                        throw IllegalStateException("Unmapped original JPA sort path: $property")
                    }
                    prefix += (if (prefix.isEmpty()) "" else ".") + relation
                    var joined = aliases[prefix]
                    if (joined == null) {
                        joined = "sort_" + (aliases.size - 1)
                        aliases[prefix] = joined
                        joins.append(if (animal) " LEFT JOIN pawbridge_animal.shelters " else " LEFT JOIN pawbridge_animal.animals ")
                            .append(joined).append(" ON ").append(joined).append(if (animal) ".id=" else ".shelter_id=")
                            .append(alias).append(if (animal) ".shelter_id" else ".id")
                    }
                    if (!animal) {
                        collectionJoin = true
                    }
                    alias = joined
                    animal = !animal
                }
                val leaf = path.last()
                val column = (if (animal) ANIMAL_COLUMNS else SHELTER_COLUMNS)[leaf]
                if (column == null) {
                    throw IllegalStateException("Unmapped original JPA sort path: $property")
                }
                var expression = "$alias.$column"
                // Enum/date/number properties do not acquire ignoreCase just because their wire form is text.
                if (ignoreCase && leafType == String::class.java) {
                    expression = "lower($expression)"
                }
                orders.add("$expression $direction")
            }
            return Sorting(
                joins.toString(),
                if (orders.isEmpty()) "" else " ORDER BY " + orders.joinToString(","),
                collectionJoin,
            )
        }

        @JvmStatic
        @Throws(SQLException::class)
        @Suppress("UNUSED_PARAMETER")
        fun animal(row: ResultSet, index: Int): Animal {
            val year = row.getObject("birth_year", Int::class.javaObjectType)
            return Animal(
                row.getObject("id", Long::class.javaObjectType),
                row.getString("apms_notice_no"),
                row.getString("species"),
                row.getString("breed"),
                row.getString("gender"),
                year,
                if (year == null) null else LocalDate.now().year - year,
                row.getString("special_mark"),
                row.getString("status"),
                row.getObject("notice_end_date", LocalDate::class.java),
                row.getString("image_url"),
                row.getObject("favorite_count", Int::class.javaObjectType),
                row.getObject("shelter_id", Long::class.javaObjectType),
                row.getString("shelter_name"),
                row.getObject("created_at", LocalDateTime::class.java),
            )
        }
    }
}
