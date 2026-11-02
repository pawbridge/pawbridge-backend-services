package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalDescriptionRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalStatusRequest
import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandForbiddenException
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalNotDeletableException
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalNotEditableException
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalPhotoStore
import com.pawbridge.backend.animal.internal.command.infrastructure.service.NoticeNumberGenerator
import jakarta.persistence.EntityNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.orm.jpa.JpaTransactionManager

/** The animal register, status, description, update and delete adapter (JPA) with manual notice numbers, on the real tables. */
class AnimalCommandJpaContractTest : JpaContractBase() {
    @Autowired
    lateinit var animals: AnimalCommandService

    @Autowired
    lateinit var searchDocuments: SearchDocumentWriter

    @Autowired
    lateinit var animalRepository: AnimalRepository

    @Autowired
    lateinit var shelterRepository: ShelterRepository

    @Autowired
    lateinit var mapper: AnimalMapper

    @Autowired
    lateinit var noticeNumbers: NoticeNumberGenerator

    @Autowired
    lateinit var actors: AnimalCommandActors

    @Autowired
    @Qualifier("animalCommandJpaTransactionManager")
    lateinit var writerTransactions: JpaTransactionManager

    private val json = ObjectMapper().findAndRegisterModules()

    @BeforeEach
    fun seed() {
        seedKeeper(ADMIN, "ROLE_ADMIN", null)
        seedKeeper(KEEPER, "ROLE_SHELTER", "CR-A")
        seedKeeper(RIVAL, "ROLE_SHELTER", "CR-B")
        seedKeeper(PLAIN, "ROLE_USER", null)
        seedKeeper(LEAVING, "ROLE_ADMIN", null)
        execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?)", LEAVING)
        seedShelter(SHELTER_A, "CR-A")
        seedShelter(SHELTER_B, "CR-B")
    }

    private fun registration(care: String = "CR-A", source: String? = null, notice: String? = null, description: String = "순한 아이") =
        json.readValue(
            json.writeValueAsString(
                mapOf(
                    "careRegNo" to care, "apmsNoticeNo" to notice, "apiSource" to source, "species" to "DOG", "gender" to "MALE",
                    "neuterStatus" to "UNKNOWN", "status" to "PROTECT", "noticeStartDate" to "2026-03-01", "noticeEndDate" to "2026-03-20",
                    "breed" to "믹스견", "color" to "갈색", "happenDate" to "2026-02-27", "happenPlace" to "합성 장소", "description" to description,
                ),
            ),
            CreateAnimalRequest::class.java,
        )

    private fun statusChange(status: String) = json.readValue("""{"newStatus":"$status"}""", UpdateAnimalStatusRequest::class.java)

    private fun descriptionChange(text: String) = json.readValue("""{"description":"$text"}""", UpdateAnimalDescriptionRequest::class.java)

    private fun detailsChange() = json.readValue(
        json.writeValueAsString(
            mapOf(
                "species" to "CAT", "gender" to "FEMALE", "neuterStatus" to "YES", "noticeStartDate" to "2026-03-02",
                "noticeEndDate" to "2026-03-25", "color" to "삼색", "happenDate" to "2026-02-28", "description" to "고친 설명",
            ),
        ),
        UpdateAnimalRequest::class.java,
    )

    /** Photo storage kept in memory; the shared context has no storage, so this wires the production beans to it. */
    private class PhotoShelf(vararg uploaded: String) : AnimalPhotoStore {
        val stored = uploaded.toMutableSet()

        override fun isUploadedPhoto(url: String) = url.startsWith(UPLOADED)

        override fun exists(url: String) = url in stored

        override fun delete(url: String) {
            stored.remove(url)
        }
    }

    private fun commandsWith(photos: AnimalPhotoStore) = AnimalCommandService(
        searchDocuments, animalRepository, shelterRepository, mapper, noticeNumbers, actors, writerTransactions, photos,
    )

    private fun registrationShowing(photo: String) = registration().also { it.imageUrl = photo }

    private fun detailsShowing(photo: String) = detailsChange().also { it.imageUrl = photo }

    private fun animalRows() = count("SELECT count(*) FROM pawbridge_animal.animals")

    @Test
    fun `보호소 담당자일 때__수동으로 등록하면__오늘 날짜의 순번 공고번호와 수동 출처와 발견 일자로 저장하고 검색 문서를 만든다`() {
        // given
        // when
        val first = animals.create(KEEPER, registration())
        val second = animals.create(ADMIN, registration())

        // then
        val numbers = listOf(first.id!!, second.id!!).map { scalar("SELECT apms_notice_no FROM pawbridge_animal.animals WHERE id = ?", it)!! }
        assertThat(numbers[0]).matches("MAN-[0-9]{6}-000001")
        assertThat(numbers[1]).isEqualTo(numbers[0].dropLast(1) + "2")
        assertThat(scalar("SELECT api_source FROM pawbridge_animal.animals WHERE id = ?", first.id!!)).isEqualTo("MANUAL")
        assertThat(scalar("SELECT happen_date::text FROM pawbridge_animal.animals WHERE id = ?", first.id!!)).isEqualTo("2026-02-27")
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animal_search_documents WHERE animal_id = ?", first.id!!)).isEqualTo(1)
    }

    @Test
    fun `중간 공고번호가 지워졌을 때__다시 등록하면__현재 최댓값 뒤로 번호를 올린다`() {
        // given
        val first = animals.create(ADMIN, registration())
        val second = animals.create(ADMIN, registration())
        val prefix = scalar("SELECT apms_notice_no FROM pawbridge_animal.animals WHERE id = ?", first.id!!)!!.dropLast(6)
        animals.delete(ADMIN, first.id)

        // when
        val third = animals.create(ADMIN, registration())

        // then
        assertThat(scalar("SELECT apms_notice_no FROM pawbridge_animal.animals WHERE id = ?", second.id!!)).isEqualTo(prefix + "000002")
        assertThat(scalar("SELECT apms_notice_no FROM pawbridge_animal.animals WHERE id = ?", third.id!!)).isEqualTo(prefix + "000003")
    }

    @Test
    fun `다른 보호소나 권한 없는 회원이거나 보호소가 없을 때__등록하면__거부하고 APMS 출처는 공고번호가 필요하다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertThatThrownBy { animals.create(KEEPER, registration(care = "CR-B")) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        for (user in listOf(PLAIN, LEAVING, UNKNOWN)) {
            assertThatThrownBy { animals.create(user, registration()) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        }
        assertThatThrownBy { animals.create(ADMIN, registration(care = "CR-NONE")) }.isInstanceOf(EntityNotFoundException::class.java)
        assertThatThrownBy { animals.create(ADMIN, registration(source = "APMS_ANIMAL")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
        val imported = animals.create(ADMIN, registration(source = "APMS_ANIMAL", notice = "경기-양평-2026-00001"))
        assertThat(scalar("SELECT apms_notice_no FROM pawbridge_animal.animals WHERE id = ?", imported.id!!)).isEqualTo("경기-양평-2026-00001")
    }

    @Test
    fun `4바이트 문자가 있을 때__등록하면__제약 위반으로 실패하고 모든 쓰기를 되돌린다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        val failure = runCatching { animals.create(ADMIN, registration(description = "😀")) }.exceptionOrNull()

        // then
        assertThat(failure).isNotNull()
        assertThat(sqlState(failure)).isEqualTo("23514")
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `소속 보호소와 다른 보호소의 동물이 있을 때__상태와 설명을 바꾸면__소속 담당자와 관리자만 바꾼다`() {
        // given
        seedAnimal(ANIMAL_A, SHELTER_A, description = "처음")
        seedAnimal(ANIMAL_B, SHELTER_B)

        // when
        val byKeeper = animals.updateStatus(KEEPER, ANIMAL_A, statusChange("ADOPTED"))
        animals.updateDescription(ADMIN, ANIMAL_A, descriptionChange("새 설명"))

        // then
        assertThat(byKeeper.status.toString()).contains("ADOPTED")
        assertThat(row("pawbridge_animal.animals", "id", ANIMAL_A).let { it.get("status").asText() to it.get("description").asText() })
            .isEqualTo("ADOPTED" to "새 설명")
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animal_search_documents WHERE animal_id = ?", ANIMAL_A)).isEqualTo(1)
        assertThatThrownBy { animals.updateStatus(KEEPER, ANIMAL_B, statusChange("ADOPTED")) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        assertThatThrownBy { animals.updateDescription(RIVAL, ANIMAL_A, descriptionChange("x")) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        assertThatThrownBy { animals.updateStatus(ADMIN, 99_999_999L, statusChange("ADOPTED")) }.isInstanceOf(EntityNotFoundException::class.java)
        assertThat(scalar("SELECT status FROM pawbridge_animal.animals WHERE id = ?", ANIMAL_B)).isEqualTo("PROTECT")
        // The status change is stamped for favorite notifications; the refused change leaves no stamp.
        assertThat(
            count(
                "SELECT count(*) FROM pawbridge_animal.animals WHERE id = ? AND previous_status = 'PROTECT'" +
                    " AND status_changed_at BETWEEN now() - interval '1 minute' AND now() + interval '1 minute'",
                ANIMAL_A,
            ),
        ).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animals WHERE id = ? AND status_changed_at IS NULL", ANIMAL_B)).isOne()
    }

    @Test
    fun `직접 등록한 동물과 APMS 동물이 있을 때__정보를 수정하면__직접 등록한 동물만 소속 담당자가 통째로 바꾼다`() {
        // given
        val manual = animals.create(KEEPER, registration())
        animals.updateStatus(KEEPER, manual.id, statusChange("ADOPTED"))
        seedAnimal(ANIMAL_A, SHELTER_A, description = "처음")
        val untouched = ContractPostgres.snapshot()

        // when
        assertThatThrownBy { animals.update(KEEPER, ANIMAL_A, detailsChange()) }.isInstanceOf(AnimalNotEditableException::class.java)
        assertThatThrownBy { animals.update(RIVAL, manual.id, detailsChange()) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        assertThat(ContractPostgres.snapshot()).isEqualTo(untouched)
        val updated = animals.update(KEEPER, manual.id, detailsChange())

        // then
        val saved = row("pawbridge_animal.animals", "id", manual.id!!)
        assertThat(listOf("species", "gender", "neuter_status", "color", "description", "status", "api_source").map { saved.get(it).asText() })
            .containsExactly("CAT", "FEMALE", "YES", "삼색", "고친 설명", "ADOPTED", "MANUAL")
        // 요청에 없는 품종과 발견 장소는 지운다.
        assertThat(saved.get("breed").isNull).isTrue()
        assertThat(saved.get("happen_place").isNull).isTrue()
        assertThat(saved.get("apms_notice_no").asText()).matches("MAN-[0-9]{6}-000001")
        assertThat(saved.get("happen_date").asText()).startsWith("2026-02-28")
        assertThat(updated.updatedAt).isNotNull()
        assertThat(scalar("SELECT description FROM pawbridge_animal.animals WHERE id = ?", ANIMAL_A)).isEqualTo("처음")
        // 검색 문서가 수정된 행의 최신 판으로 다시 만들어졌다.
        assertThat(
            count(
                "SELECT count(*) FROM pawbridge_animal.animal_search_documents d JOIN pawbridge_animal.animals a ON a.id = d.animal_id " +
                    "WHERE a.id = ? AND d.source_revision = a.search_revision",
                manual.id!!,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `챗봇 대화가 달린 동물과 달리지 않은 동물이 있을 때__삭제하면__달린 동물은 외래키로 실패하고 나머지는 지운다`() {
        // given
        seedAnimal(ANIMAL_A, SHELTER_A, apiSource = "MANUAL")
        seedAnimal(ANIMAL_B, SHELTER_A, apiSource = "MANUAL")
        execute(
            "INSERT INTO pawbridge_animal.chatbot_sessions(id, anonymous_session_id, animal_id, created_at) VALUES ('s-1', 'a-1', ?, '2026-03-01 00:00:00')",
            ANIMAL_A,
        )

        // when
        animals.delete(KEEPER, ANIMAL_B)
        val failure = runCatching { animals.delete(KEEPER, ANIMAL_A) }.exceptionOrNull()

        // then
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animals WHERE id = ?", ANIMAL_B)).isZero()
        assertThat(sqlState(failure)).isEqualTo("23503")
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animals WHERE id = ?", ANIMAL_A)).isEqualTo(1)
        assertThatThrownBy { animals.delete(RIVAL, ANIMAL_A) }.isInstanceOf(AnimalCommandForbiddenException::class.java)
        assertThat(animalRows()).isEqualTo(1)
    }

    @Test
    fun `APMS 동물일 때__보호소나 관리자가 삭제하면__삭제 불가로 거부하고 행을 남긴다`() {
        // given
        seedAnimal(ANIMAL_A, SHELTER_A)
        val before = ContractPostgres.snapshot()

        // when
        // then
        assertThatThrownBy { animals.delete(KEEPER, ANIMAL_A) }.isInstanceOf(AnimalNotDeletableException::class.java)
        assertThatThrownBy { animals.delete(ADMIN, ANIMAL_A) }.isInstanceOf(AnimalNotDeletableException::class.java)
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `올린 사진으로 등록하고 바꾸고 삭제할 때__명령을 실행하면__아무도 안 쓰게 된 사진만 저장소에서 지운다`() {
        // given
        val shelf = PhotoShelf(PHOTO_A, PHOTO_B)
        val commands = commandsWith(shelf)
        val registered = commands.create(KEEPER, registrationShowing(PHOTO_A))

        // when
        val takenByAnother = runCatching { commands.create(KEEPER, registrationShowing(PHOTO_A)) }.exceptionOrNull()
        commands.update(KEEPER, registered.id, detailsShowing(PHOTO_B))
        val staleForm = runCatching { commands.update(KEEPER, registered.id, detailsShowing(PHOTO_A)) }.exceptionOrNull()
        val afterReplace = shelf.stored.toSet()
        commands.delete(KEEPER, registered.id)

        // then
        assertThat(takenByAnother).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(staleForm).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(afterReplace).containsExactly(PHOTO_B)
        assertThat(shelf.stored).isEmpty()
        assertThat(animalRows()).isZero()
    }

    @Test
    fun `두 동물이 같은 사진을 보일 때__한 동물의 사진을 바꾸면__다른 동물이 쓰는 사진은 남긴다`() {
        // given
        seedAnimal(ANIMAL_A, SHELTER_A, apiSource = "MANUAL", imageUrl = PHOTO_A)
        seedAnimal(ANIMAL_B, SHELTER_A, apiSource = "MANUAL", imageUrl = PHOTO_A)
        val shelf = PhotoShelf(PHOTO_A, PHOTO_B)

        // when
        commandsWith(shelf).update(KEEPER, ANIMAL_A, detailsShowing(PHOTO_B))

        // then
        assertThat(shelf.stored).containsExactlyInAnyOrder(PHOTO_A, PHOTO_B)
        assertThat(scalar("SELECT image_url FROM pawbridge_animal.animals WHERE id = ?", ANIMAL_A)).isEqualTo(PHOTO_B)
    }

    private companion object {
        const val UPLOADED = "https://assets.contract.invalid/animals/"
        const val PHOTO_A = UPLOADED + "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png"
        const val PHOTO_B = UPLOADED + "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.png"
        const val ADMIN = 8_200_001L
        const val KEEPER = 8_200_002L
        const val RIVAL = 8_200_003L
        const val PLAIN = 8_200_004L
        const val LEAVING = 8_200_005L
        const val UNKNOWN = 8_200_099L
        const val SHELTER_A = 8_200_101L
        const val SHELTER_B = 8_200_102L
        const val ANIMAL_A = 8_200_201L
        const val ANIMAL_B = 8_200_202L
    }
}
