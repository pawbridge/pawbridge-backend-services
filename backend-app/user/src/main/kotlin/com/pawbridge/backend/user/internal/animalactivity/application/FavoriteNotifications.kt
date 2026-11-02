package com.pawbridge.backend.user.internal.animalactivity.application

import java.time.Instant

/**
 * Status changes of a member's favorite animals made after the animal was favorited, newest first.
 * Only each animal's latest change is known; a change stays listed for 15 Korean calendar days including its day.
 */
fun interface FavoriteNotifications {
    @JvmRecord
    data class Notification(
        val animalId: Long,
        val status: String,
        val previousStatus: String,
        val changedAt: Instant,
        val species: String?,
        val breed: String?,
        val imageUrl: String?,
        val apmsNoticeNo: String?,
        val shelterName: String?,
    )

    @JvmRecord
    data class Notifications(val content: List<Notification>)

    fun recent(currentUserId: Long): Notifications
}
