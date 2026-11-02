package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.api.AnimalMemberReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.animal.api.AnimalMemberReads.Animal
import com.pawbridge.backend.animal.api.AnimalMemberReads.PageRequest
import com.pawbridge.backend.animal.api.AnimalMemberReads.StatusChange
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.internal.memberread.infrastructure.JdbcAnimalMemberReads
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The animal reads the user module uses (favorite batch and a shelter's manual animals), on the real tables.
 * Ported from the former `OwnedPostgresqlAnimalMemberReadTest` (fixture rows and sort-path cases are the same).
 */
class AnimalMemberReadRepositoryContractTest : AnimalRepositoryContractBase() {
    private val created = "2026-10-20 01:02:03.123456"
    private val createdAt = LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123_456_000)
    private val end = LocalDate.of(2026, 11, 1)
    private lateinit var reads: AnimalMemberReads

    @BeforeEach
    fun seed() {
        reads = JdbcAnimalMemberReads(
            source,
            manager,
            object : AnimalShelterReads {
                override fun findByCareRegNo(careRegNo: String?): AnimalShelterReads.Shelter? = null
            },
        )
        seedShelter(SHELTER_A, name = "owned-a")
        seedShelter(SHELTER_B, name = "owned-b")
        member(MANUAL_A, SHELTER_A, "MANUAL", "ADOPTED")
        member(APMS_NOTICE_A, SHELTER_A, "APMS_ANIMAL", "NOTICE")
        member(APMS_ADOPTED_A, SHELTER_A, "APMS_ANIMAL", "ADOPTED")
        member(MANUAL_B, SHELTER_B, "MANUAL", "PROTECT", breed = "synthetic-breed", year = LocalDate.now().year - 3, mark = "synthetic-mark", image = "https://example.invalid/owned-image", favorites = 5)
        member(MANUAL_ADOPTED_B, SHELTER_B, "MANUAL", "ADOPTED")
    }

    @Test
    fun `없는 동물과 중복과 null이 섞였을 때__배치 조회하면__없는 동물은 빼고 DTO 값 15개를 유지한다`() {
        // given
        val requested = listOf(MANUAL_A, MANUAL_B, MISSING, MANUAL_A, APMS_ADOPTED_A, null)

        // when
        val batch = reads.batch(requested)!!
        val empty = reads.batch(listOf())

        // then
        val byId: Map<Long?, Animal> = batch.associateBy { it.id }
        assertThat(byId.keys).isEqualTo(setOf(MANUAL_A, MANUAL_B, APMS_ADOPTED_A))
        assertThat(byId[MANUAL_A]).isEqualTo(
            Animal(MANUAL_A, "N-$MANUAL_A", "CAT", null, "UNKNOWN", null, null, null, "ADOPTED", end, null, 0, SHELTER_A, "owned-a", createdAt),
        )
        assertThat(byId[MANUAL_B]).isEqualTo(
            Animal(
                MANUAL_B, "N-$MANUAL_B", "CAT", "synthetic-breed", "UNKNOWN", LocalDate.now().year - 3, 3, "synthetic-mark", "PROTECT",
                end, "https://example.invalid/owned-image", 5, SHELTER_B, "owned-b", createdAt,
            ),
        )
        assertThat(empty).isEmpty()
    }

    @Test
    fun `수동 루트와 연관 APMS가 있을 때__컬렉션 정렬로 쪼개 읽으면__LIMIT 뒤에 루트 중복을 제거하고 부분 건수를 센다`() {
        // given
        // Shelter A has one MANUAL root and two APMS children. Sorting through the animals collection joins three
        // rows for one root; the SQL LIMIT applies before the root deduplication.

        // when
        val first = reads.byShelter(SHELTER_A, PageRequest(0, 2, "shelter.animals.id,asc"))!!
        val second = reads.byShelter(SHELTER_A, PageRequest(1, 2, "shelter.animals.id,asc"))!!
        val empty = reads.byShelter(SHELTER_A, PageRequest(2, 2, "shelter.animals.id,asc"))!!

        // then
        assertThat(first.content!!.map { it.id }).isEqualTo(listOf(MANUAL_A))
        assertPage(first, 0, 2, 1, 1, true)
        assertThat(second.content!!.map { it.id }).isEqualTo(listOf(MANUAL_A))
        assertPage(second, 1, 2, 3, 2, true)
        assertThat(empty.content).isEmpty()
        assertPage(empty, 2, 2, 1, 1, true)
        for (sort in listOf("shelter_animals_id,asc", "ShelterAnimalsId,asc", "shelter.animals,asc", "shelter.animals.shelter.animals.id,asc")) {
            val page = reads.byShelter(SHELTER_A, PageRequest(0, 2, sort))!!
            assertThat(page.content!!.map { it.id }).`as`(sort).isEqualTo(listOf(MANUAL_A))
            assertPage(page, 0, 2, 1, 1, true)
        }
    }

    @Test
    fun `수동 루트 둘과 다른 보호소가 있을 때__꽉 찬 페이지와 빈 페이지를 읽으면__수동 루트만 세고 종료 루트도 유지한다`() {
        // given
        // when
        val first = reads.byShelter(SHELTER_B, PageRequest(0, 1, "id,asc"))!!
        val second = reads.byShelter(SHELTER_B, PageRequest(1, 1, "id,asc"))!!
        val empty = reads.byShelter(SHELTER_B, PageRequest(2, 1, "id,asc"))!!
        val full = reads.byShelter(SHELTER_B, PageRequest(0, 2, "shelter.animals.id,asc"))!!
        val newest = reads.byShelter(SHELTER_B, PageRequest(0, 5, null))!!

        // then
        assertThat(first.content!!.map { it.id }).isEqualTo(listOf(MANUAL_B))
        assertPage(first, 0, 1, 2, 2, false)
        assertThat(second.content!!.map { it.id }).isEqualTo(listOf(MANUAL_ADOPTED_B))
        assertPage(second, 1, 1, 2, 2, true)
        assertThat(empty.content).isEmpty()
        assertPage(empty, 2, 1, 2, 2, true)
        assertThat(full.content!!.map { it.id }.toSet()).isEqualTo(setOf(MANUAL_B, MANUAL_ADOPTED_B))
        assertPage(full, 0, 2, 2, 1, true)
        assertThat(newest.content).hasSize(2)
    }

    @Test
    fun `쪽 번호나 크기가 어긋나거나 알 수 없는 정렬일 때__보호소 동물을 읽으면__사용할 수 없음 예외다`() {
        // given
        // when
        // then
        for (request in listOf(PageRequest(-1, 2, null), PageRequest(0, 0, null), PageRequest(0, 2, "password,asc"))) {
            assertThatThrownBy { reads.byShelter(SHELTER_A, request) }.isInstanceOf(AnimalMemberReadUnavailableException::class.java)
        }
    }

    @Test
    fun `상태가 바뀌거나 같은 값이거나 미상이거나 다른 열만 바뀔 때__갱신하면__실제 상태 변화만 이전 상태와 시각을 남긴다`() {
        // given
        // MANUAL_B is PROTECT, MANUAL_A ADOPTED, APMS_NOTICE_A NOTICE, APMS_ADOPTED_A ADOPTED.

        // when
        execute("UPDATE pawbridge_animal.animals SET status='ADOPTED' WHERE id=?", MANUAL_B)
        execute("UPDATE pawbridge_animal.animals SET status='ADOPTED' WHERE id=?", MANUAL_A)
        execute("UPDATE pawbridge_animal.animals SET status='UNKNOWN' WHERE id=?", APMS_NOTICE_A)
        execute("UPDATE pawbridge_animal.animals SET favorite_count=favorite_count+1 WHERE id=?", APMS_ADOPTED_A)

        // then
        assertThat(
            count(
                "SELECT count(*) FROM pawbridge_animal.animals WHERE id=? AND previous_status='PROTECT'" +
                    " AND status_changed_at BETWEEN now() - interval '1 minute' AND now()",
                MANUAL_B,
            ),
        ).isOne()
        assertThat(
            count(
                "SELECT count(*) FROM pawbridge_animal.animals WHERE id IN (?,?,?)" +
                    " AND (previous_status IS NOT NULL OR status_changed_at IS NOT NULL)",
                MANUAL_A, APMS_NOTICE_A, APMS_ADOPTED_A,
            ),
        ).isZero()
    }

    @Test
    fun `미상이었다가 알려진 상태로 돌아올 때__갱신하면__미상을 이전 상태로 남긴다`() {
        // given
        execute("UPDATE pawbridge_animal.animals SET status='UNKNOWN' WHERE id=?", MANUAL_B)

        // when
        execute("UPDATE pawbridge_animal.animals SET status='PROTECT' WHERE id=?", MANUAL_B)

        // then
        // Only the latest change is kept, so the trigger cannot know PROTECT was the status before UNKNOWN.
        assertThat(
            count(
                "SELECT count(*) FROM pawbridge_animal.animals WHERE id=? AND status='PROTECT'" +
                    " AND previous_status='UNKNOWN' AND status_changed_at IS NOT NULL",
                MANUAL_B,
            ),
        ).isOne()
    }

    @Test
    @Tag("timezone-sensitive")
    fun `변경 시각이 기준 앞뒤로 있을 때__최근 변경을 읽으면__기준 시각 이후의 요청한 동물만 준다`() {
        // given
        changedAt(MANUAL_A, "PROTECT", "2026-11-02 03:00:00.123456+00")
        changedAt(MANUAL_B, "NOTICE", "2026-10-18 14:59:59.999999+00")
        changedAt(APMS_ADOPTED_A, "PROTECT", "2026-10-18 15:00:00+00")
        val since = Instant.parse("2026-10-18T15:00:00Z")

        // when
        val changes = reads.statusChanges(listOf(MANUAL_A, MANUAL_B, APMS_ADOPTED_A, MANUAL_ADOPTED_B, MISSING), since)
        val other = reads.statusChanges(listOf(MANUAL_B), since)
        val empty = reads.statusChanges(listOf(), since)

        // then
        assertThat(changes.sortedBy { it.animalId }).containsExactly(
            StatusChange(MANUAL_A, "ADOPTED", "PROTECT", Instant.parse("2026-11-02T03:00:00.123456Z")),
            StatusChange(APMS_ADOPTED_A, "ADOPTED", "PROTECT", since),
        )
        assertThat(other).isEmpty()
        assertThat(empty).isEmpty()
    }

    // Writes the change columns directly; the trigger fires only on status updates.
    private fun changedAt(id: Long, previous: String, at: String) {
        execute(
            "UPDATE pawbridge_animal.animals SET previous_status=?, status_changed_at=?::timestamptz WHERE id=?",
            previous, at, id,
        )
    }

    private fun assertPage(page: AnimalMemberReads.Page, number: Int, size: Int, total: Long, pages: Int, last: Boolean) {
        assertThat(listOf(page.number, page.size, page.totalElements, page.totalPages, page.last))
            .isEqualTo(listOf(number, size, total, pages, last))
    }

    private fun member(
        id: Long, shelter: Long, source: String, status: String,
        breed: String? = null, year: Int? = null, mark: String? = null, image: String? = null, favorites: Int = 0,
    ) {
        execute(
            "INSERT INTO pawbridge_animal.animals(id,apms_notice_no,species,breed,birth_year,gender,neuter_status,special_mark," +
                "notice_start_date,notice_end_date,image_url,shelter_id,status,api_source,favorite_count,created_at) " +
                "VALUES (?,?,'CAT',?,?,'UNKNOWN','UNKNOWN',?,'2026-10-01','2026-11-01',?,?,?,?,?,?::timestamp)",
            id, "N-$id", breed, year, mark, image, shelter, status, source, favorites, created,
        )
    }

    private companion object {
        const val BASE = 9_010_000L
        const val SHELTER_A = BASE + 1
        const val SHELTER_B = BASE + 2
        const val MANUAL_A = BASE + 101
        const val APMS_NOTICE_A = BASE + 102
        const val APMS_ADOPTED_A = BASE + 103
        const val MANUAL_B = BASE + 104
        const val MANUAL_ADOPTED_B = BASE + 105
        const val MISSING = BASE + 999
    }
}
