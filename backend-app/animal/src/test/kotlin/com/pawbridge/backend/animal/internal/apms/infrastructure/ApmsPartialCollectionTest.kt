package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsBody
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsHeader
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsItems
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsResponse
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsRootResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.JobExecution
import java.time.LocalDate

class ApmsPartialCollectionTest {
    @Test
    fun `991건이 보고됐지만 매번 같은 990건만 반환될 때__수집하면__제공자 불일치로 기록하고 완료한다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val day = LocalDate.of(2026, 9, 10)
        val execution = JobExecution(1L, ApmsSyncPlan(day, day, day, day).parameters())
        val items = (0 until 990).map { i ->
            ApmsAnimal.builder().desertionNo("id-" + i).happenDt("20260910").updTm("2026-09-10 10:00:00").build()
        }

        every {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            if ("20260910" == arg<String>(4)) {
                response(991, items, arg<Int>(1))
            } else {
                response(0, listOf(), arg<Int>(1))
            }
        }

        // when
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)

        // then
        assertThat(snapshot.animals()).hasSize(990)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero()
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.PROVIDER_GAP_COUNT)).isPositive()
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.PROVIDER_GAP_DETAILS))
            .contains("reported=991", "observed=990")
    }

    @Test
    fun `한 구간에서 전송 실패가 났을 때__수집하면__정상 구간은 유지하고 인증 정보를 노출하지 않는다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val day = LocalDate.of(2026, 9, 10)
        val execution = JobExecution(1L, ApmsSyncPlan(day, day, day, day).parameters())
        val animal = ApmsAnimal.builder().desertionNo("healthy").happenDt("20260910").build()

        every {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            if (arg<String?>(8) != null) {
                throw IllegalStateException("secret-placeholder")
            }
            response(1, listOf(animal), arg<Int>(1))
        }

        // when
        val animals = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000).animals()

        // then
        assertThat(animals).containsExactly(animal)
        assertThat(execution.executionContext.getInt("apms.sync.incompleteQueryCount")).isEqualTo(1)
        assertThat(execution.executionContext.getString("apms.sync.incompleteQueryDetails"))
            .contains("REQUEST_FAILED").doesNotContain("secret-placeholder")
    }

    @Test
    fun `일시적으로 잘린 응답이 재시도에서 빠진 동물을 돌려줄 때__수집하면__중복 쓰기 없이 완료한다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        val one = animal("one", "20260909")
        val two = animal("two", "20260910")

        every {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { response(0, listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null)
        } returnsMany listOf(response(2, listOf(one), 1), response(2, listOf(one, two), 1))

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).containsExactly(one, two)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero()
        verify(exactly = 2) {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null)
        }
    }

    @Test
    fun `잘린 구간을 이틀로 나눠 기대한 동물을 모두 맞출 수 있을 때__수집하면__완료한다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        val one = animal("one", "20260909")
        val two = animal("two", "20260910")

        every {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { response(0, listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null)
        } returns response(2, listOf(one), 1)
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260909", null, null, "json", null, null)
        } returns response(1, listOf(one), 1)
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260910", "20260910", null, null, "json", null, null)
        } returns response(1, listOf(two), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals.map { animal -> animal.desertionNo }).containsExactly("one", "two")
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero()
    }

    @Test
    fun `나눈 하위 구간의 합계가 상위 보고보다 적을 때__수집하면__미해결로 남긴다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        val one = animal("one", "20260909")
        val two = animal("two", "20260910")

        every {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { response(0, listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null)
        } returns response(3, listOf(one), 1)
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260909", null, null, "json", null, null)
        } returns response(1, listOf(one), 1)
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260910", "20260910", null, null, "json", null, null)
        } returns response(1, listOf(two), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).hasSize(2)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS))
            .contains("reported=3", "observed=2", "COUNT_MISMATCH")
    }

    @Test
    fun `정상 조회 뒤 요청 예산이 소진됐을 때__수집하면__데이터는 유지하고 조회하지 못한 구간을 기록한다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 1, 50000)
        val one = animal("one", "20260909")

        every {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { response(0, listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null)
        } returns response(1, listOf(one), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).containsExactly(one)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS))
            .contains("REQUEST_BUDGET")
        verify(exactly = 1) {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), null, null, "json", any(), any())
        }
    }

    @Test
    fun `앞 구간이 실패하고 예산이 작을 때__수집하면__실패한 구간을 재시도하기 전에 다른 구간을 시도한다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 2, 50000)
        val one = animal("one", "20260909")

        every {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { response(0, listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null)
        } throws IllegalStateException("offline")
        every {
            api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260901", "20260910", null, null, "json", "20260909", "20260910",
            )
        } returns response(1, listOf(one), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).containsExactly(one)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        verify(exactly = 1) {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null)
        }
    }

    @Test
    fun `제공자가 다음 쪽 대신 같은 ID를 반복해 꽉 찬 쪽이 되풀이될 때__수집하면__완료라고 주장하지 않는다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        val items = (0 until 1000).map { i -> animal("id-" + i, "20260909") }

        every {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { response(0, listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", any(), 1000, "20260909", "20260910", null, null, "json", null, null)
        } answers { response(2000, items, arg<Int>(1)) }

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).hasSize(1000)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        verify(exactly = 0) {
            api.getAbandonmentAnimals(any(), 3, any(), any(), any(), null, null, "json", any(), any())
        }
    }

    @Test
    fun `합계에 닿기 전에 둘째 쪽이 비었을 때__수집하면__첫 쪽은 유지하고 빈틈을 기록한다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        val items = (0 until 1000).map { i -> animal("id-" + i, "20260909") }

        every {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { response(0, listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260909", "20260910", null, null, "json", null, null)
        } returns response(1001, items, 1)
        every {
            api.getAbandonmentAnimals("test-key", 2, 1000, "20260909", "20260910", null, null, "json", null, null)
        } returns response(1001, listOf(), 2)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).hasSize(1000)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
    }

    @Test
    fun `마지막 쪽에서 매번 같은 1마리가 빠질 때__수집하면__같은 조회를 한 번 더 받고 제공자 불일치로 완료한다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        stubEmptyWindows(api)
        every { intakePage(api, 1) } returns response(1002, fullPage(), 1)
        every { intakePage(api, 2) } returns response(1002, listOf(animal("last-a", "20260910")), 2)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).hasSize(1001)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero()
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.PROVIDER_GAP_COUNT)).isEqualTo(1)
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.PROVIDER_GAP_DETAILS))
            .contains("intake=2026-09-09..2026-09-10", "reported=1002", "observed=1001")
        verify(exactly = 2) { intakePage(api, 1) }
        verify(exactly = 2) { intakePage(api, 2) }
    }

    @Test
    fun `첫 조회가 마지막 쪽에서 끊기고 이후 두 번 같은 1마리가 빠질 때__수집하면__제공자 불일치로 완료한다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        val lastPage = response(1002, listOf(animal("last-a", "20260910")), 2)
        stubEmptyWindows(api)
        every { intakePage(api, 1) } returns response(1002, fullPage(), 1)
        every { intakePage(api, 2) } throws IllegalStateException("offline") andThen lastPage andThen lastPage

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).hasSize(1001)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero()
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.PROVIDER_GAP_COUNT)).isEqualTo(1)
        verify(exactly = 3) { intakePage(api, 2) }
    }

    @Test
    fun `다시 받았을 때 빠졌던 1마리가 채워질 때__수집하면__제공자 불일치 없이 완료한다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        stubEmptyWindows(api)
        every { intakePage(api, 1) } returns response(1002, fullPage(), 1)
        every { intakePage(api, 2) } returnsMany listOf(
            response(1002, listOf(animal("last-a", "20260910")), 2),
            response(1002, listOf(animal("last-a", "20260910"), animal("last-b", "20260910")), 2),
        )

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).hasSize(1002)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isZero()
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.PROVIDER_GAP_COUNT)).isZero()
    }

    @Test
    fun `두 번 모두 1마리가 빠지지만 받은 동물이 서로 다를 때__수집하면__미해결로 남긴다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        stubEmptyWindows(api)
        every { intakePage(api, 1) } returns response(1002, fullPage(), 1)
        every { intakePage(api, 2) } returnsMany listOf(
            response(1002, listOf(animal("last-a", "20260910")), 2),
            response(1002, listOf(animal("last-b", "20260910")), 2),
        )

        // when
        snapshot.animals()

        // then
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.PROVIDER_GAP_COUNT)).isZero()
    }

    @Test
    fun `마지막이 아닌 첫 쪽에서 1마리가 빠질 때__수집하면__제공자 불일치로 보지 않고 미해결로 남긴다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        stubEmptyWindows(api)
        every { intakePage(api, 1) } returns response(1001, fullPage().drop(1), 1)
        every { intakePage(api, 2) } returns response(1001, listOf(animal("last-a", "20260910")), 2)

        // when
        snapshot.animals()

        // then
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.PROVIDER_GAP_COUNT)).isZero()
    }

    @Test
    fun `마지막 쪽에서 매번 같은 2마리가 빠질 때__수집하면__미해결로 남긴다`() {
        // given
        val api = mockk<ApmsApiClient>()
        val execution = executionFor(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        val snapshot = ApmsAnimalSnapshot(api, "test-key", execution, 100, 50000)
        stubEmptyWindows(api)
        every { intakePage(api, 1) } returns response(1003, fullPage(), 1)
        every { intakePage(api, 2) } returns response(1003, listOf(animal("last-a", "20260910")), 2)

        // when
        snapshot.animals()

        // then
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isEqualTo(1)
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS))
            .contains("reported=1003", "observed=1001")
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.PROVIDER_GAP_COUNT)).isZero()
    }

    private fun stubEmptyWindows(api: ApmsApiClient) {
        every {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { response(0, listOf(), arg<Int>(1)) }
    }

    private fun intakePage(api: ApmsApiClient, page: Int): ApmsRootResponse<ApmsAnimal>? {
        return api.getAbandonmentAnimals("test-key", page, 1000, "20260909", "20260910", null, null, "json", null, null)
    }

    private fun fullPage(): List<ApmsAnimal> {
        return (0 until 1000).map { i -> animal("id-" + i, "20260909") }
    }

    private fun executionFor(start: LocalDate, end: LocalDate): JobExecution {
        return JobExecution(1L, ApmsSyncPlan(start, start, start, end).parameters())
    }

    private fun animal(number: String, date: String): ApmsAnimal {
        return ApmsAnimal.builder().desertionNo(number).happenDt(date).updTm("2026-09-10 10:00:00").build()
    }

    private fun response(total: Int, items: List<ApmsAnimal>, page: Int): ApmsRootResponse<ApmsAnimal> {
        return ApmsRootResponse(
            ApmsResponse(
                ApmsHeader.builder().resultCode("00").build(),
                ApmsBody(ApmsItems(items), "1000", page.toString(), total.toString()),
            ),
        )
    }
}
