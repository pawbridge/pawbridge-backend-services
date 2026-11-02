package com.pawbridge.backend.user.internal.animalactivity.infrastructure

import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteNotifications
import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteNotifications.Notification
import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteNotifications.Notifications
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Animal
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.AnimalLookup
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.StatusChange
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

class JdbcFavoriteNotifications(
    private val jdbc: JdbcOperations,
    private val read: TransactionOperations,
    private val animals: AnimalLookup,
    private val clock: Clock,
) : FavoriteNotifications {

    // Animal lookups run after the User read transaction has returned its connection, as for the favorite list.
    override fun recent(currentUserId: Long): Notifications {
        val favoritedAt: Map<Long, Instant> = read.execute {
            jdbc.query(
                SELECT_FAVORITES,
                RowMapper { rs, _ -> rs.getLong("animal_id") to rs.getTimestamp("created_at", utc()).toInstant() },
                currentUserId,
            ).toMap()
        }!!
        if (favoritedAt.isEmpty()) {
            return Notifications(listOf())
        }
        // A change before the favorite was made is not news to the member.
        val changes = animals.statusChanges(favoritedAt.keys.toList(), windowStart())
            .filter { change -> change.changedAt.isAfter(favoritedAt.getValue(change.animalId)) }
        if (changes.isEmpty()) {
            return Notifications(listOf())
        }
        val byId: Map<Long?, Animal> = animals.batch(changes.map { it.animalId })!!.filterNotNull().associateBy { it.id }
        // An animal removed between the two reads has nothing to show and is skipped.
        val content = changes
            .mapNotNull { change -> byId[change.animalId]?.let { animal -> notification(change, animal) } }
            .sortedByDescending { it.changedAt }
        return Notifications(content)
    }

    // The change day is day one: a change on the 2nd is listed through the 16th and gone at 00:00 on the 17th.
    private fun windowStart(): Instant =
        LocalDate.ofInstant(clock.instant(), SEOUL).minusDays(WINDOW_DAYS - 1).atStartOfDay(SEOUL).toInstant()

    private fun notification(change: StatusChange, animal: Animal) = Notification(
        change.animalId,
        change.status,
        change.previousStatus,
        change.changedAt,
        animal.species,
        animal.breed,
        animal.imageUrl,
        animal.apmsNoticeNo,
        animal.shelterName,
    )

    private companion object {
        private const val SELECT_FAVORITES = "SELECT animal_id,created_at FROM pawbridge_user.favorites WHERE user_id=?"
        private const val WINDOW_DAYS = 15L
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        // Favorites store the UTC wall clock in a timestamp without time zone.
        private fun utc(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    }
}
