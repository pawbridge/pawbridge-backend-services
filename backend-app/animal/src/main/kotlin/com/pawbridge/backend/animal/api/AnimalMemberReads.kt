package com.pawbridge.backend.animal.api

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Animal reads used by already-authorized User activities and shelter membership. */
interface AnimalMemberReads : AnimalShelterReads {
    @JvmRecord
    data class Animal(
        val id: Long?,
        val apmsNoticeNo: String?,
        val species: String?,
        val breed: String?,
        val gender: String?,
        val birthYear: Int?,
        val age: Int?,
        val specialMark: String?,
        val status: String?,
        val noticeEndDate: LocalDate?,
        val imageUrl: String?,
        val favoriteCount: Int?,
        val shelterId: Long?,
        val shelterName: String?,
        val createdAt: LocalDateTime?,
    )

    /** sort is the single flattened HTTP sort value emitted by the existing User controller. */
    @JvmRecord
    data class PageRequest(
        val page: Int,
        val size: Int,
        val sort: String?,
    )

    @JvmRecord
    data class Page(
        val content: List<Animal>?,
        val number: Int,
        val size: Int,
        val totalElements: Long,
        val totalPages: Int,
        val last: Boolean,
    )

    /** The latest status change of an animal, recorded by the animals trigger (V10); older changes are not kept. */
    @JvmRecord
    data class StatusChange(
        val animalId: Long,
        val status: String,
        val previousStatus: String,
        val changedAt: Instant,
    )

    fun batch(ids: List<Long?>?): List<Animal>?

    /** Animals among [ids] whose latest status change is at or after [since]; any other animal is absent. */
    fun statusChanges(ids: List<Long>, since: Instant): List<StatusChange>

    fun byShelter(shelterId: Long?, request: PageRequest?): Page?
}
