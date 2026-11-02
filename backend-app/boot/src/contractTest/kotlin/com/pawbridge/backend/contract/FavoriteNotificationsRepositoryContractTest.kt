package com.pawbridge.backend.contract

import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.Animal
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.AnimalLookup
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities.StatusChange
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.JdbcFavoriteNotifications
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Favorite notifications called directly. The favorite rows run on the real database; the animal module
 * (latest status changes and summaries) is a mock.
 */
class FavoriteNotificationsRepositoryContractTest : UserRepositoryContractBase() {
    private val animals = mockk<AnimalLookup>()

    @BeforeEach
    fun seed() {
        insertUser(MEMBER, nickname = "알림받는회원")
        insertUser(OTHER, nickname = "다른회원")
    }

    @Test
    @Tag("timezone-sensitive")
    fun `관심 등록 전후로 상태가 바뀌고 한 동물은 사라졌을 때__알림을 읽으면__등록 뒤 변화만 최신순으로 준다`() {
        // given
        insertFavorite(MEMBER, 11, "2026-10-30 10:00:00")
        insertFavorite(MEMBER, 12, "2026-11-05 00:00:00")
        insertFavorite(MEMBER, 14, "2026-10-30 10:00:00")
        insertFavorite(MEMBER, 15, "2026-10-30 10:00:00")
        insertFavorite(OTHER, 16, "2026-10-30 10:00:00")
        every { animals.statusChanges(any(), any()) } returns listOf(
            change(11, "2026-11-03T01:00:00Z"),
            change(12, "2026-11-04T23:59:59Z"),
            change(14, "2026-11-10T00:00:00Z"),
            change(15, "2026-11-12T00:00:00Z"),
        )
        every { animals.batch(any()) } returns listOf(animal(11), animal(15))

        // when
        val notifications = notifications(at("2026-11-16T14:59:59Z")).recent(MEMBER)

        // then
        // Animal 12 changed before it was favorited; animal 14 disappeared between the two animal reads.
        assertThat(notifications.content.map { it.animalId }).containsExactly(15L, 11L)
        val latest = notifications.content.first()
        assertThat(listOf(latest.status, latest.previousStatus, latest.changedAt.toString(), latest.breed, latest.shelterName))
            .containsExactly("ADOPTED", "PROTECT", "2026-11-12T00:00:00Z", "믹스-15", "행복 보호소")
        verify(exactly = 1) {
            animals.statusChanges(match { it.toSet() == setOf(11L, 12L, 14L, 15L) }, Instant.parse("2026-11-01T15:00:00Z"))
        }
        verify(exactly = 1) { animals.batch(match { it.toSet() == setOf(11L, 14L, 15L) }) }
    }

    @Test
    @Tag("timezone-sensitive")
    fun `한국 날짜가 넘어갈 때__알림을 읽으면__바뀐 날을 포함한 15일째까지만 기준에 넣는다`() {
        // given
        insertFavorite(MEMBER, 11, "2026-10-30 10:00:00")
        every { animals.statusChanges(any(), any()) } returns listOf()

        // when
        notifications(at("2026-11-16T14:59:59Z")).recent(MEMBER)
        notifications(at("2026-11-16T15:00:00Z")).recent(MEMBER)

        // then
        // 23:59:59 on the 16th (KST) still lists changes from the 2nd; 00:00 on the 17th starts at the 3rd.
        verify(exactly = 1) { animals.statusChanges(listOf(11L), Instant.parse("2026-11-01T15:00:00Z")) }
        verify(exactly = 1) { animals.statusChanges(listOf(11L), Instant.parse("2026-11-02T15:00:00Z")) }
        verify(exactly = 0) { animals.batch(any()) }
    }

    @Test
    fun `관심 동물이 없을 때__알림을 읽으면__동물 조회 없이 빈 목록이다`() {
        // given
        insertFavorite(OTHER, 16, "2026-10-30 10:00:00")

        // when
        val notifications = notifications(at("2026-11-16T14:59:59Z")).recent(MEMBER)

        // then
        assertThat(notifications.content).isEmpty()
        verify(exactly = 0) { animals.statusChanges(any(), any()) }
        verify(exactly = 0) { animals.batch(any()) }
        assertNoTransactionOpen()
    }

    private fun notifications(clock: Clock) = JdbcFavoriteNotifications(write, readOnlyTransaction(), animals, clock)

    private fun at(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)

    // created_at holds the UTC wall clock, as the favorite writer stores it.
    private fun insertFavorite(userId: Long, animalId: Long, utcWallClock: String) {
        execute(
            "INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES (?,?,?::timestamp)",
            userId, animalId, utcWallClock,
        )
    }

    private fun change(animalId: Long, at: String) = StatusChange(animalId, "ADOPTED", "PROTECT", Instant.parse(at))

    private fun animal(id: Long) = Animal(
        id, "N-$id", "개", "믹스-$id", "수컷", 2022, 4, null, "ADOPTED", null, "https://image.invalid/$id", 1, 5, "행복 보호소",
        LocalDateTime.of(2026, 1, 1, 0, 0, 1),
    )

    private companion object {
        const val MEMBER = 9_972_001L
        const val OTHER = 9_972_002L
    }
}
