package com.pawbridge.backend.animal.internal.command.infrastructure.service

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalDescriptionRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalStatusRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/** Selected original JSON commands; Root supplies one Animal JPA writer and current-account lookup. */
class AnimalCommandService(
    private val searchDocuments: SearchDocumentWriter,
    private val animalRepository: AnimalRepository,
    private val shelterRepository: ShelterRepository,
    private val mapper: AnimalMapper,
    private val noticeNumberGenerator: NoticeNumberGenerator,
    private val actors: AnimalCommandActors,
    writerTransactions: JpaTransactionManager,
    /** Uploaded photo storage; null while photo uploads are disabled. */
    private val photos: AnimalPhotoStore? = null,
    /** Stamps status changes for favorite notifications. */
    private val clock: Clock = Clock.systemUTC(),
) {
    private val writes: TransactionTemplate

    init {
        if (writerTransactions.dataSource !== searchDocuments.dataSource()) {
            throw IllegalArgumentException("Animal JPA and search must use the same writer DataSource")
        }
        writes = TransactionTemplate(writerTransactions)
        writes.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun create(verifiedUserId: Long?, request: CreateAnimalRequest): AnimalDetailResponse {
        val requestedPhotos = listOf(request.imageUrl, request.imageUrl2)
        // Storage lookups run before the write transaction so no row lock is held during the network call.
        val storedPhotos = storedUploadedPhotos(requestedPhotos)
        return writes.execute { _ ->
            val actor = currentActor(verifiedUserId)
            requireShelter(actor, request.careRegNo)
            requireAttachablePhotos(requestedPhotos, emptySet(), storedPhotos)
            create(request)
        }!!
    }

    fun updateStatus(verifiedUserId: Long?, id: Long?, request: UpdateAnimalStatusRequest): AnimalDetailResponse {
        return writes.execute { _ -> updateStatus(ownedAnimal(verifiedUserId, id), request) }!!
    }

    fun updateDescription(verifiedUserId: Long?, id: Long?, request: UpdateAnimalDescriptionRequest): AnimalDetailResponse {
        return writes.execute { _ -> updateDescription(ownedAnimal(verifiedUserId, id), request) }!!
    }

    fun update(verifiedUserId: Long?, id: Long?, request: UpdateAnimalRequest): AnimalDetailResponse {
        val requestedPhotos = listOf(request.imageUrl, request.imageUrl2)
        // Only addresses the animal does not show yet are looked up in storage, so an edit that keeps its photos
        // never depends on storage being reachable.
        val newPhotos = requestedPhotos - currentPhotosWithoutLock(id, requestedPhotos)
        val storedPhotos = storedUploadedPhotos(newPhotos)
        val updated = writes.execute { _ -> update(ownedAnimal(verifiedUserId, id), request, storedPhotos) }!!
        cleanUpPhotos(updated.replacedPhotos)
        return updated.response
    }

    fun delete(verifiedUserId: Long?, id: Long?) {
        val removedPhotos = writes.execute { _ ->
            val animal = ownedAnimal(verifiedUserId, id)
            if (animal.apiSource != ApiSource.MANUAL) {
                throw AnimalNotDeletableException()
            }
            val animalPhotos = listOfNotNull(animal.imageUrl, animal.imageUrl2)
            animalRepository.deleteById(id ?: throw IllegalArgumentException("The given id must not be null"))
            animalPhotos
        }!!
        cleanUpPhotos(removedPhotos)
    }

    private fun currentActor(verifiedUserId: Long?): Actor {
        if (verifiedUserId == null) {
            throw AnimalCommandForbiddenException()
        }
        val actor: Actor? = actors.find(verifiedUserId)
        if (actor == null ||
            actor.userId != verifiedUserId ||
            !actor.active ||
            actor.deleted ||
            !("ROLE_ADMIN" == actor.role || "ROLE_SHELTER" == actor.role)
        ) {
            throw AnimalCommandForbiddenException()
        }
        return actor
    }

    private fun ownedAnimal(verifiedUserId: Long?, id: Long?): Animal {
        val actor = currentActor(verifiedUserId)
        val animal = animalRepository.findByIdForUpdate(id)
            .orElseThrow { EntityNotFoundException("Animal not found: $id") }
        requireShelter(actor, animal.shelter?.careRegNo)
        return animal
    }

    private fun requireShelter(actor: Actor, careRegNo: String?) {
        if ("ROLE_ADMIN" == actor.role) {
            return
        }
        val actorCareRegNo = actor.careRegNo
        if (actorCareRegNo == null || isBlank(actorCareRegNo) || actorCareRegNo != careRegNo) {
            throw AnimalCommandForbiddenException()
        }
    }

    private fun create(request: CreateAnimalRequest): AnimalDetailResponse {
        // Shelter 조회 (careRegNo로)
        val shelter = shelterRepository.findByCareRegNo(request.careRegNo)
            .orElseThrow { EntityNotFoundException("Shelter not found: " + request.careRegNo) }

        // apiSource 기본값 처리 (미입력 시 MANUAL)
        if (request.apiSource == null) {
            request.apiSource = ApiSource.MANUAL
        }

        if (request.apiSource != ApiSource.MANUAL && hasNoNoticeNumber(request)) {
            throw IllegalArgumentException("수동 등록 외 데이터는 공고번호가 필요합니다.")
        }

        // 수동 등록 시 공고번호 자동 생성
        if (request.apiSource == ApiSource.MANUAL && hasNoNoticeNumber(request)) {
            request.apmsNoticeNo = noticeNumberGenerator.generate()
        }

        // DTO → Entity 변환
        val animal = mapper.toEntity(request, shelter)

        // 저장
        val saved = animalRepository.save(animal)
        refreshSearch(saved.id)

        // Entity → Response DTO
        return mapper.toDetailResponse(saved)
    }

    private fun updateStatus(animal: Animal, request: UpdateAnimalStatusRequest): AnimalDetailResponse {
        animal.updateStatus(request.newStatus, clock.instant())

        animalRepository.flush()
        return mapper.toDetailResponse(animal)
    }

    private fun updateDescription(animal: Animal, request: UpdateAnimalDescriptionRequest): AnimalDetailResponse {
        animal.updateDescription(request.description)
        refreshSearch(animal.id)

        return mapper.toDetailResponse(animal)
    }

    private fun update(animal: Animal, request: UpdateAnimalRequest, storedPhotos: Set<String>): Updated {
        // APMS 동물은 다음 동기화가 덮어쓰므로, 권한을 확인한 뒤 출처로 한 번 더 거른다.
        if (animal.apiSource != ApiSource.MANUAL) {
            throw AnimalNotEditableException()
        }
        val currentPhotos = setOfNotNull(animal.imageUrl, animal.imageUrl2)
        requireAttachablePhotos(listOf(request.imageUrl, request.imageUrl2), currentPhotos, storedPhotos)
        animal.updateManualDetails(
            noticeStartDate = request.noticeStartDate,
            noticeEndDate = request.noticeEndDate,
            species = request.species,
            gender = request.gender,
            neuterStatus = request.neuterStatus,
            breed = request.breed,
            birthYear = request.birthYear,
            weight = request.weight,
            color = request.color,
            specialMark = request.specialMark,
            happenDate = request.happenDate,
            happenPlace = request.happenPlace,
            imageUrl = request.imageUrl,
            imageUrl2 = request.imageUrl2,
            description = request.description,
        )
        refreshSearch(animal.id)

        val replacedPhotos = currentPhotos - setOfNotNull(request.imageUrl, request.imageUrl2)
        return Updated(mapper.toDetailResponse(animal), replacedPhotos)
    }

    /** The animal's current photo addresses, read only when the request names an uploaded photo at all. */
    private fun currentPhotosWithoutLock(id: Long?, requestedPhotos: List<String?>): Set<String> {
        val store = photos ?: return emptySet()
        val namesUploadedPhoto = requestedPhotos.any { url -> url != null && store.isUploadedPhoto(url) }
        if (!namesUploadedPhoto) {
            return emptySet()
        }
        val slots = writes.execute { _ -> animalRepository.findPhotoSlots(id) } ?: return emptySet()
        return setOfNotNull(slots.imageUrl, slots.imageUrl2)
    }

    /** Uploaded photo addresses among these that exist in storage right now. */
    private fun storedUploadedPhotos(urls: Collection<String?>): Set<String> {
        val store = photos ?: return emptySet()
        return urls.filterNotNull()
            .distinct()
            .filter { url -> store.isUploadedPhoto(url) && store.exists(url) }
            .toSet()
    }

    /**
     * A photo slot may keep its current address or take a photo this service uploaded that exists in storage and
     * that no animal shows yet. Anything else (another animal's photo, an outside address, a photo already cleaned
     * up because a stale form sends it back) is refused, so cleanup never removes a photo someone else shows.
     * A new address that was not looked up in storage (the animal showed it when read without a lock, but no longer
     * does under the lock) is refused too.
     *
     * Known limit: the storage lookup runs before the lock. If a stale form sends back photo A while another edit of
     * the same animal has just replaced A and its cleanup has not deleted A yet, the stale form can reattach A and the
     * cleanup then deletes it. The window runs from that commit until its cleanup finishes (a reference count and one
     * storage delete, tens to hundreds of milliseconds).
     */
    private fun requireAttachablePhotos(urls: List<String?>, currentPhotos: Set<String>, storedPhotos: Set<String>) {
        for (url in urls) {
            if (url == null || url in currentPhotos) {
                continue
            }
            val store = photos
            val attachable = store != null &&
                store.isUploadedPhoto(url) &&
                url in storedPhotos &&
                animalRepository.countPhotoReferences(url) == 0L
            if (!attachable) {
                throw IllegalArgumentException("등록할 수 없는 사진 주소입니다. 사진을 다시 올려 주세요.")
            }
        }
    }

    /**
     * Runs after the write committed. Deletes uploaded photos that no animal shows any more. A failure here is
     * logged without the address and never turns the committed request into an error.
     */
    private fun cleanUpPhotos(urls: Collection<String>) {
        val store = photos ?: return
        for (url in urls.distinct()) {
            try {
                if (!store.isUploadedPhoto(url)) {
                    continue
                }
                val references = writes.execute { _ -> animalRepository.countPhotoReferences(url) }!!
                if (references == 0L) {
                    store.delete(url)
                }
            } catch (failure: Exception) {
                log.warn("Animal photo cleanup after commit failed: {}", failure.javaClass.simpleName)
            }
        }
    }

    private fun refreshSearch(id: Long?) {
        animalRepository.flush()
        // Java unboxed the id here, so a missing id fails with a NullPointerException.
        searchDocuments.animal(id!!)
    }

    private fun hasNoNoticeNumber(request: CreateAnimalRequest): Boolean {
        val noticeNumber = request.apmsNoticeNo
        return noticeNumber == null || isBlank(noticeNumber)
    }

    private class Updated(
        val response: AnimalDetailResponse,
        val replacedPhotos: Set<String>,
    )

    private companion object {
        private val log = LoggerFactory.getLogger(AnimalCommandService::class.java)

        // Java String.isBlank() uses Character.isWhitespace; Kotlin isBlank() also accepts NBSP.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }
    }
}
