package com.pawbridge.backend.user.internal.animalactivity.infrastructure

import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import java.time.Instant

/** Replaces all three activity HTTP calls only when Root explicitly selects the module contract. */
class ModuleUserAnimalLookup(private val animals: AnimalMemberReads) : UserAnimalActivities.AnimalLookup {

    override fun batch(ids: List<Long?>): List<UserAnimalActivities.Animal>? {
        val result = animals.batch(ids) ?: return null
        return mapAnimals(result)
    }

    override fun shelter(careRegNo: String?): UserAnimalActivities.Shelter? {
        val result = animals.findByCareRegNo(careRegNo) ?: return null
        return UserAnimalActivities.Shelter(result.id, result.name)
    }

    override fun byShelter(
        shelterId: Long?,
        request: UserAnimalActivities.PageRequest,
    ): UserAnimalActivities.AnimalPage? {
        val page = animals.byShelter(
            shelterId,
            AnimalMemberReads.PageRequest(request.page, request.size, request.sort),
        ) ?: return null
        val content = page.content
        // Existing HTTP maps Spring Page.number to an absent User.page field, leaving page=0.
        return UserAnimalActivities.AnimalPage(
            if (content == null) null else mapAnimals(content),
            0,
            page.size,
            page.totalElements,
            page.totalPages,
            page.last,
        )
    }

    override fun statusChanges(ids: List<Long>, since: Instant): List<UserAnimalActivities.StatusChange> =
        animals.statusChanges(ids, since).map { change ->
            UserAnimalActivities.StatusChange(change.animalId, change.status, change.previousStatus, change.changedAt)
        }

    // A null source element stays a null element, as with the Java stream. Stream.toList() also keeps the
    // result unmodifiable like before; the unchecked cast only widens the element type for Kotlin.
    @Suppress("UNCHECKED_CAST")
    private fun mapAnimals(rows: List<AnimalMemberReads.Animal?>): List<UserAnimalActivities.Animal> =
        rows.stream().map { row -> animal(row) }.toList() as List<UserAnimalActivities.Animal>

    private fun animal(row: AnimalMemberReads.Animal?): UserAnimalActivities.Animal? {
        if (row == null) {
            return null
        }
        return UserAnimalActivities.Animal(
            row.id,
            row.apmsNoticeNo,
            row.species,
            row.breed,
            row.gender,
            row.birthYear,
            row.age,
            row.specialMark,
            row.status,
            row.noticeEndDate,
            row.imageUrl,
            row.favoriteCount,
            row.shelterId,
            row.shelterName,
            row.createdAt,
        )
    }
}
