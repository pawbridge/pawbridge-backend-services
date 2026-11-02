package com.pawbridge.backend.user.internal.animalactivity.infrastructure

import com.pawbridge.backend.animal.api.AnimalMemberReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.shelter.infrastructure.ModuleShelterLookup
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class ModuleUserAnimalLookupTest {
    private val animals: AnimalMemberReads = mockk<AnimalMemberReads>()
    private val lookup = ModuleUserAnimalLookup(animals)

    @Test
    fun `모든 필드와 null이 담긴 묶음일 때__매핑하면__모든 DTO 값을 유지하고 입력을 그대로 위임한다`() {
        // given
        val end = LocalDate.of(2026, 12, 31)
        val created = LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000)
        val row = AnimalMemberReads.Animal(8L, "공고", "DOG", "품종", "MALE", 2020, 6, "특징", "ADOPTED", end, "image", 5, 7L, "센터", created)
        val nullable = AnimalMemberReads.Animal(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
        val ids: List<Long?> = listOf(8L, 8L, 404L, null)

        // The Java contract allows null elements even though Kotlin types the list as non-null.
        @Suppress("UNCHECKED_CAST")
        val rows = listOf(row, nullable, null) as List<AnimalMemberReads.Animal>

        every { animals.batch(any()) } returns rows

        // when
        val result = lookup.batch(ids)

        // then
        assertIterableEquals(
            listOf(
                UserAnimalActivities.Animal(8L, "공고", "DOG", "품종", "MALE", 2020, 6, "특징", "ADOPTED", end, "image", 5, 7L, "센터", created),
                UserAnimalActivities.Animal(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                null,
            ),
            result,
        )
        verify(exactly = 1) { animals.batch(match { it === ids }) }
        confirmVerified(animals)
    }

    @Test
    fun `최근 상태 변경이 주어졌을 때__조회하면__기준 시각과 동물을 그대로 넘기고 이전·현재 상태를 바꾸지 않고 옮긴다`() {
        // given
        val since = Instant.parse("2026-11-01T15:00:00Z")
        val changed = Instant.parse("2026-11-03T01:00:00Z")
        every { animals.statusChanges(any(), any()) } returns listOf(AnimalMemberReads.StatusChange(8L, "ADOPTED", "PROTECT", changed))

        // when
        val result = lookup.statusChanges(listOf(8L, 9L), since)

        // then
        assertIterableEquals(listOf(UserAnimalActivities.StatusChange(8L, "ADOPTED", "PROTECT", changed)), result)
        verify(exactly = 1) { animals.statusChanges(listOf(8L, 9L), since) }
        confirmVerified(animals)
    }

    @Test
    fun `실제 페이지 번호와 원본 정렬이 주어졌을 때__보호소별로 조회하면__기존 전송 규약의 페이지 0과 나머지 메타데이터를 유지한다`() {
        // given
        val request = UserAnimalActivities.PageRequest(3, 20, "shelter.animals.id,asc")

        every { animals.byShelter(any(), any()) } returns AnimalMemberReads.Page(listOf(), 3, 20, 61, 4, true)

        // when
        val result = lookup.byShelter(7L, request)

        // then
        assertEquals(UserAnimalActivities.AnimalPage(listOf(), 0, 20, 61, 4, true), result)
        verify(exactly = 1) {
            animals.byShelter(7L, AnimalMemberReads.PageRequest(3, 20, "shelter.animals.id,asc"))
        }
    }

    @Test
    fun `통합된 동물 계약일 때__두 사용자 소비자가 보호소를 찾으면__같은 API를 쓰고 서로 다른 투영을 유지한다`() {
        // given
        every { animals.findByCareRegNo(any()) } returns AnimalShelterReads.Shelter(7L, "r", "센터")

        // when
        val activityShelter = lookup.shelter(" r ")
        val lookedUp = ModuleShelterLookup(animals).find(" r ")

        // then
        assertEquals(UserAnimalActivities.Shelter(7L, "센터"), activityShelter)
        assertEquals("r", lookedUp!!.careRegNo)
        verify(exactly = 2) { animals.findByCareRegNo(" r ") }
    }

    @Test
    fun `본문이 없거나 내용이 null일 때__매핑하면__호출자 쪽 사용 불가와 널 허용 의미를 유지한다`() {
        // given
        val request = UserAnimalActivities.PageRequest(0, 20, "id,asc")

        every { animals.batch(any()) } returns null
        every { animals.findByCareRegNo(any()) } returns null
        every { animals.byShelter(any(), any()) } returnsMany
            listOf(null, AnimalMemberReads.Page(null, 2, 20, 5, 1, true))

        // when
        val batch = lookup.batch(listOf(8L))
        val shelter = lookup.shelter("r")
        val missingPage = lookup.byShelter(7L, request)
        val emptyContent = lookup.byShelter(7L, request)

        // then
        assertNull(batch)
        assertNull(shelter)
        assertNull(missingPage)
        assertNull(emptyContent!!.content)
    }

    @Test
    fun `읽기 실패가 날 때__어떤 활동 호출이든 하면__기존 호출자 실패 정책을 유지한다`() {
        // given
        val failure = AnimalMemberReadUnavailableException(IllegalStateException("read unavailable"))

        every { animals.batch(any()) } throws failure
        every { animals.byShelter(any(), any()) } throws failure
        every { animals.findByCareRegNo(any()) } throws failure

        // when
        val batchFailure = assertThrows(AnimalMemberReadUnavailableException::class.java) { lookup.batch(listOf(8L)) }
        val shelterFailure = assertThrows(AnimalMemberReadUnavailableException::class.java) { lookup.shelter("r") }
        val pageFailure = assertThrows(AnimalMemberReadUnavailableException::class.java) {
            lookup.byShelter(7L, UserAnimalActivities.PageRequest(0, 20, "id,asc"))
        }

        // then
        assertSame(failure, batchFailure)
        assertSame(failure, shelterFailure)
        assertSame(failure, pageFailure)
    }
}
