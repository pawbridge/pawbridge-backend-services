package com.pawbridge.backend.user.internal.animalactivity.application

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

interface UserAnimalActivities {

    @JvmRecord
    data class Favorite(
        val favoriteId: Long?,
        val userId: Long?,
        val animalId: Long?,
        val createdAt: LocalDateTime?,
    )

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

    @JvmRecord
    data class FavoriteAnimal(
        val favoriteId: Long?,
        val userId: Long?,
        val animalId: Long?,
        val createdAt: LocalDateTime?,
        val breed: String?,
        val species: String?,
        val gender: String?,
        val age: Int?,
        val imageUrl: String?,
        val shelterName: String?,
        val status: String?,
        val apmsNoticeNo: String?,
        val specialMark: String?,
    )

    @JvmRecord
    data class Favorites(
        val userId: Long?,
        val totalCount: Int,
        val favorites: List<FavoriteAnimal>?,
    )

    @JvmRecord
    data class AnimalPage(
        val content: List<Animal>?,
        val page: Int,
        val size: Int,
        val totalElements: Long,
        val totalPages: Int,
        val last: Boolean,
    )

    @JvmRecord
    data class PageRequest(
        val page: Int,
        val size: Int,
        val sort: String?,
    )

    @JvmRecord
    data class Shelter(
        val id: Long?,
        val name: String?,
    )

    @JvmRecord
    data class StatusChange(
        val animalId: Long,
        val status: String,
        val previousStatus: String,
        val changedAt: Instant,
    )

    // The lists may contain null elements, as the Java signatures allowed (the module lookup test passes them).
    interface AnimalLookup {
        fun batch(ids: List<Long?>): List<Animal>?

        fun shelter(careRegNo: String?): Shelter?

        fun byShelter(shelterId: Long?, request: PageRequest): AnimalPage?

        /** Latest status change at or after [since] of each listed animal that has one. */
        fun statusChanges(ids: List<Long>, since: Instant): List<StatusChange>
    }

    fun add(currentUserId: Long, animalId: Long): Favorite

    fun remove(currentUserId: Long, animalId: Long)

    fun favorites(currentUserId: Long): Favorites

    fun registered(currentUserId: Long, request: PageRequest): AnimalPage
}
