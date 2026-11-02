package com.pawbridge.backend.animal.internal.apms.infrastructure.writer

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.batch.item.Chunk
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AnimalItemWriterTest {
    @Test
    fun `신규 동물과 상태가 바뀐 기존 동물이 섞인 청크일 때__쓰면__신규만 삽입하고 검색 반영 전에 APMS 필드와 이전 상태·시각으로 갱신한다`() {
        // given
        val repository = mockk<AnimalRepository>()
        val search = mockk<SearchDocumentWriter>()
        val shelter = Shelter.builder().id(11L).careRegNo("owned").build()
        val fresh = Animal.builder().apmsDesertionNo("new").shelter(shelter).build()
        val old = Animal.builder().id(21L).shelter(shelter).breed("owned breed").happenDate(LocalDate.of(2026, 9, 10))
            .imageUrl("owned-image").imageUrl2("owned-extra").status(AnimalStatus.PROTECT).build()
        val now = Instant.parse("2026-11-02T03:00:00Z")

        every { repository.saveAll(any<List<Animal>>()) } answers {
            ReflectionTestUtils.setField(fresh, "id", 31L)
            firstArg<List<Animal>>()
        }
        every {
            repository.updateAnimalFromApms(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns 1
        every { repository.findStatuses(listOf(21L)) } returns listOf(arrayOf(21L, AnimalStatus.ADOPTION_PENDING))
        justRun { repository.flush() }
        justRun { search.animal(any()) }

        // when
        AnimalItemWriter(search, repository, Clock.fixed(now, ZoneOffset.UTC)).write(Chunk(listOf(fresh, old)))

        // then
        verify(exactly = 1) { repository.saveAll(listOf(fresh)) }
        verify(exactly = 1) {
            repository.updateAnimalFromApms(
                21L, refEq(shelter), "owned breed", any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), LocalDate.of(2026, 9, 10), any(), "owned-image", "owned-extra",
                AnimalStatus.PROTECT, AnimalStatus.ADOPTION_PENDING, now,
            )
        }
        verifyOrder {
            repository.flush()
            search.animal(31L)
            search.animal(21L)
        }
        verify(exactly = 1) { repository.flush() }
        verify(exactly = 1) { search.animal(31L) }
        verify(exactly = 1) { search.animal(21L) }
        verify(exactly = 0) { repository.save(any<Animal>()) }
    }

    @Test
    fun `flush가 실패할 때__청크를 쓰면__어떤 검색 반영보다 먼저 예외를 전파한다`() {
        // given
        val repository = mockk<AnimalRepository>()
        val search = mockk<SearchDocumentWriter>()
        val chunk = Chunk(listOf(Animal.builder().build()))

        every { repository.saveAll(any<List<Animal>>()) } returns listOf()
        every { repository.flush() } throws IllegalStateException("synthetic flush failure")

        // when
        val thrown = assertThatThrownBy { AnimalItemWriter(search, repository).write(chunk) }

        // then
        thrown.hasMessage("synthetic flush failure")
        confirmVerified(search)
    }
}
