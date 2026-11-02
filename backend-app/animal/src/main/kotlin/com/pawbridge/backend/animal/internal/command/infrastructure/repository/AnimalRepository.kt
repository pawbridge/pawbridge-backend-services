package com.pawbridge.backend.animal.internal.command.infrastructure.repository

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Gender
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.NeuterStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import java.time.Instant

interface AnimalRepository : JpaRepository<Animal, Long> {
    @EntityGraph(attributePaths = ["shelter"])
    fun findWithShelterById(id: Long?): Optional<Animal>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Animal a where a.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long?): Optional<Animal>

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :day)", nativeQuery = true)
    fun lockManualNoticePrefix(@Param("namespace") namespace: Int, @Param("day") day: Int): Int

    @Query(
        value = "SELECT COALESCE(MAX(CAST(substring(apms_notice_no FROM :pattern) AS integer)), 0) " +
            "FROM pawbridge_animal.animals WHERE apms_notice_no LIKE :prefix",
        nativeQuery = true,
    )
    fun findMaxManualNoticeSuffix(@Param("prefix") prefix: String?, @Param("pattern") pattern: String?): Int

    @EntityGraph(attributePaths = ["shelter"])
    fun findWithShelterByIdIn(ids: List<Long>): List<Animal>

    /** The photo addresses of one animal, read without a lock to find which requested addresses are new. */
    @Query(
        "SELECT new com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalPhotoSlots(" +
            "a.imageUrl, a.imageUrl2) FROM Animal a WHERE a.id = :id",
    )
    fun findPhotoSlots(@Param("id") id: Long?): AnimalPhotoSlots?

    /** How many animals show this photo address in either photo slot. */
    @Query("SELECT count(a) FROM Animal a WHERE a.imageUrl = :url OR a.imageUrl2 = :url")
    fun countPhotoReferences(@Param("url") url: String): Long

    @Query("SELECT MIN(a.happenDate) FROM Animal a WHERE a.apiSource = :source")
    fun findEarliestHappenDate(@Param("source") source: ApiSource?): LocalDate?

    @Query("SELECT a.apmsDesertionNo, a.id FROM Animal a WHERE a.apmsDesertionNo IS NOT NULL")
    fun findAllApmsDesertionNoAndId(): List<Array<Any?>>

    /**
     * APMS 배치 기존 Animal 직접 UPDATE - SELECT 없이 id 기반 UPDATE만 실행
     * - merge()의 SELECT → UPDATE 2단계를 UPDATE 1단계로 단축
     * - @Modifying(clearAutomatically = true): 쿼리 후 1차 캐시 초기화로 스테일 방지
     */
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE Animal a SET " +
            "a.breed = :breed, a.birthYear = :birthYear, a.weight = :weight, " +
            "a.color = :color, a.gender = :gender, a.neuterStatus = :neuterStatus, " +
            "a.specialMark = :specialMark, a.apmsProcessState = :apmsProcessState, " +
            "a.noticeStartDate = :noticeStartDate, a.noticeEndDate = :noticeEndDate, " +
            "a.apmsUpdatedAt = :apmsUpdatedAt, a.happenDate = :happenDate, " +
            "a.happenPlace = :happenPlace, a.imageUrl = :imageUrl, a.imageUrl2 = :imageUrl2, " +
            "a.shelter = :shelter, a.status = :status, " +
            "a.previousStatus = COALESCE(:previousStatus, a.previousStatus), " +
            "a.statusChangedAt = COALESCE(:statusChangedAt, a.statusChangedAt) WHERE a.id = :id",
    )
    fun updateAnimalFromApms(
        @Param("id") id: Long?,
        @Param("shelter") shelter: Shelter?,
        @Param("breed") breed: String?,
        @Param("birthYear") birthYear: Int?,
        @Param("weight") weight: String?,
        @Param("color") color: String?,
        @Param("gender") gender: Gender?,
        @Param("neuterStatus") neuterStatus: NeuterStatus?,
        @Param("specialMark") specialMark: String?,
        @Param("apmsProcessState") apmsProcessState: String?,
        @Param("noticeStartDate") noticeStartDate: LocalDate?,
        @Param("noticeEndDate") noticeEndDate: LocalDate?,
        @Param("apmsUpdatedAt") apmsUpdatedAt: LocalDateTime?,
        @Param("happenDate") happenDate: LocalDate?,
        @Param("happenPlace") happenPlace: String?,
        @Param("imageUrl") imageUrl: String?,
        @Param("imageUrl2") imageUrl2: String?,
        @Param("status") status: AnimalStatus?,
        @Param("previousStatus") previousStatus: AnimalStatus?,
        @Param("statusChangedAt") statusChangedAt: Instant?,
    ): Int

    /** 동기화 묶음의 현재 상태. 갱신 전에 읽어 상태 변경 여부를 판단한다. */
    @Query("SELECT a.id, a.status FROM Animal a WHERE a.id IN :ids")
    fun findStatuses(@Param("ids") ids: Collection<Long>): List<Array<Any?>>
}
