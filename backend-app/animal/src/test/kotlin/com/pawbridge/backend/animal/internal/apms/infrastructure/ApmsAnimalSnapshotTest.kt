package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsBody
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsHeader
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsItems
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsResponse
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsRootResponse
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import org.springframework.batch.core.JobExecution
import org.springframework.batch.support.transaction.ResourcelessTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

class ApmsAnimalSnapshotTest {
    private val api = mockk<ApmsApiClient>()

    private lateinit var execution: JobExecution
    private val plan = ApmsSyncPlan(
        LocalDate.of(2026, 8, 11),
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 8, 11),
        LocalDate.of(2026, 9, 10),
    )

    @Test
    fun `활성 쓰기 트랜잭션 안에서 캐시되지 않은 스냅샷을 가져올 때__동물을 조회하면__제공자 호출 전에 거부한다`() {
        // given
        val uncached = snapshot()
        val transaction = TransactionTemplate(ResourcelessTransactionManager())

        // when
        val thrown = assertThatThrownBy { transaction.execute { status -> uncached.animals() } }

        // then
        thrown
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("APMS external fetch must run outside the writer transaction")
        confirmVerified(api)
    }

    @Test
    fun `입양으로 종료된 오래된 입소 건이 최근 갱신됐을 때__수집하면__보호 상태 필터 없이 포함한다`() {
        // given
        val old = animal("old", "20260715", "2026-08-23 15:21:46.0", "종료(입양)")
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260701", "20260731", null, null, "json", "20260811", "20260910",
            )
        } returns page("1", listOf(old), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals.map { animal -> animal.processState }).containsExactly("종료(입양)")
        verify(exactly = 1) {
            api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260701", "20260731", null, null, "json", "20260811", "20260910",
            )
        }
    }

    @Test
    fun `갱신 시각이 없는 신규 동물일 때__수집하면__입소일 경로로 유지한다`() {
        // given
        val fresh = animal("new", "20260908", null, "보호중")
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("1", listOf(fresh), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).containsExactly(fresh)
    }

    @Test
    fun `같은 신규 번호가 더 나중 상태로 중복될 때__수집하면__가장 최신 동물 하나만 쓴다`() {
        // given
        val first = animal("new", "20260908", "2026-09-08 10:00:00.0", "보호중")
        val latest = animal("new", "20260908", "2026-09-09 10:00:00.123", "종료(입양)")
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("1", listOf(first), 1)
        every {
            api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260901", "20260910", null, null, "json", "20260811", "20260910",
            )
        } returns page("1", listOf(latest), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).containsExactly(latest)
    }

    @Test
    fun `이미 종료된 동물이 정정됐을 때__수집하면__가장 최신 정정을 유지한다`() {
        // given
        val latest = animal("ended", "20260908", "2026-09-10 10:00:00", "종료(반환)")
        val stale = animal("ended", "20260908", "2026-09-09 10:00:00", "종료(입양)")
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("1", listOf(latest), 1)
        every {
            api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260901", "20260910", null, null, "json", "20260811", "20260910",
            )
        } returns page("1", listOf(stale), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).containsExactly(latest)
    }

    @Test
    fun `같은 시각에 상태가 충돌할 때__수집하면__동물 쓰기 전에 실패한다`() {
        // given
        val protecting = animal("one", "20260908", "2026-09-09 10:00:00", "보호중")
        val adopted = animal("one", "20260908", "2026-09-09 10:00:00.000", "종료(입양)")
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("1", listOf(protecting), 1)
        every {
            api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260901", "20260910", null, null, "json", "20260811", "20260910",
            )
        } returns page("1", listOf(adopted), 1)

        // when
        val thrown = assertThatThrownBy { snapshot.animals() }

        // then
        thrown.hasMessageContaining("conflicting data")
    }

    @Test
    fun `동등한 시각 형식일 때__수집하면__중복을 제거한다`() {
        // given
        val plain = animal("one", "20260908", "2026-09-09 10:00:00", "보호중")
        val withMillis = animal("one", "20260908", "2026-09-09 10:00:00.000", "보호중")
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("1", listOf(plain), 1)
        every {
            api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260901", "20260910", null, null, "json", "20260811", "20260910",
            )
        } returns page("1", listOf(withMillis), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).hasSize(1)
    }

    @Test
    fun `공고 번호만 같고 동물 번호가 다를 때__수집하면__둘 다 유지한다`() {
        // given
        val one = animal("one", "20260908", null, "보호중")
        val two = animal("two", "20260908", null, "보호중")
        one.noticeNo = "same-notice"
        two.noticeNo = "same-notice"
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("2", listOf(one, two), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).hasSize(2)
    }

    @Test
    fun `두 단계가 스냅샷을 함께 쓸 때__두 번 읽으면__한 번만 가져온다`() {
        // given
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }

        // when
        val first = snapshot.animals()
        val second = snapshot.animals()

        // then
        assertThat(second).isSameAs(first)
        verify(exactly = 4) {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        }
    }

    @Test
    fun `여러 쪽이 있을 때__수집하면__각 쪽을 읽고 마지막 쪽에서 멈춘다`() {
        // given
        val first = (0 until 1000).map { i -> animal("id-" + i, "20260908", null, "보호중") }
        val last = animal("last", "20260908", null, "보호중")
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("1001", first, 1)
        every {
            api.getAbandonmentAnimals("test-key", 2, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("1001", listOf(last), 2)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).hasSize(1001)
        verify(exactly = 0) {
            api.getAbandonmentAnimals(any(), 3, any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `전송 실패가 났을 때__수집하면__요청 내용을 노출하지 않고 불완전으로 기록한다`() {
        // given
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", any(), 1000, any(), any(), null, null, "json", null, null)
        } throws IllegalStateException("private-service-key-placeholder")

        // when
        snapshot.animals()

        // then
        assertIncomplete("REQUEST_FAILED")
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS))
            .doesNotContain("private-service-key-placeholder")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompletePages")
    fun `건수가 맞지 않거나 응답이 비정상일 때__수집하면__불완전으로 기록한다`(
        name: String,
        firstPage: () -> ApmsRootResponse<ApmsAnimal>?,
        reason: String,
        everyWindow: Boolean,
    ) {
        // given
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        if (everyWindow) {
            every {
                api.getAbandonmentAnimals("test-key", any(), 1000, any(), any(), null, null, "json", null, null)
            } returns firstPage()
        } else {
            every {
                api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
            } returns firstPage()
        }

        // when
        snapshot.animals()

        // then
        assertIncomplete(reason)
    }

    @Test
    fun `성공했지만 건수가 0일 때__수집하면__빈 결과를 받아들인다`() {
        // given
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("0", null, 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).isEmpty()
    }

    @Test
    fun `요청 예산에 도달했을 때__다른 조회가 더 필요하면__추가 호출 없이 조회하지 못한 구간을 기록한다`() {
        // given
        val snapshot = snapshot(1, 50000)

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals).isEmpty()
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS)).contains("REQUEST_BUDGET")
        verify(exactly = 1) {
            api.getAbandonmentAnimals(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `동물 예산에 도달했을 때__새 번호가 들어오면__제한된 데이터는 유지하고 불완전으로 기록한다`() {
        // given
        val one = animal("one", "20260908", null, "보호중")
        val two = animal("two", "20260908", null, "보호중")
        val snapshot = snapshot(100, 1)

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("2", listOf(one, two), 1)

        // when
        val animals = snapshot.animals()

        // then
        assertThat(animals.map { animal -> animal.desertionNo }).containsExactly("one")
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isPositive()
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS)).contains("ANIMAL_BUDGET")
    }

    @Test
    fun `제공자가 수정일 필터를 무시할 때__수집하면__실패한다`() {
        // given
        val outsideWindow = animal("old", "20260715", "2026-07-16 10:00:00", "보호중")
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals(
                "test-key", 1, 1000, "20260701", "20260731", null, null, "json", "20260811", "20260910",
            )
        } returns page("1", listOf(outsideWindow), 1)

        // when
        val thrown = assertThatThrownBy { snapshot.animals() }

        // then
        thrown.hasMessageContaining("update window")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAnimals")
    fun `동물 번호나 갱신 시각이 올바르지 않을 때__수집하면__보정하거나 임의의 중복을 고르지 않고 실패한다`(
        name: String,
        invalid: ApmsAnimal,
        message: String,
    ) {
        // given
        val snapshot = snapshot()

        every {
            api.getAbandonmentAnimals(any(), any(), 1000, any(), any(), null, null, "json", any(), any())
        } answers { page("0", listOf(), arg<Int>(1)) }
        every {
            api.getAbandonmentAnimals("test-key", 1, 1000, "20260811", "20260910", null, null, "json", null, null)
        } returns page("1", listOf(invalid), 1)

        // when
        val thrown = assertThatThrownBy { snapshot.animals() }

        // then
        thrown.hasMessageContaining(message)
    }

    private fun assertIncomplete(reason: String) {
        assertThat(execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT)).isPositive()
        assertThat(execution.executionContext.getString(ApmsAnimalSnapshot.INCOMPLETE_DETAILS)).contains(reason)
    }

    private fun snapshot(): ApmsAnimalSnapshot {
        return snapshot(100, 50000)
    }

    private fun snapshot(requests: Int, animals: Int): ApmsAnimalSnapshot {
        execution = JobExecution(1L, plan.parameters())
        return ApmsAnimalSnapshot(api, "test-key", execution, requests, animals)
    }

    companion object {
        @JvmStatic
        fun incompletePages(): Stream<Arguments> {
            val providerError = page("0", listOf(), 1)
            providerError.response!!.header!!.resultCode = "30"
            val one = animal("one", "20260908", null, "보호중")
            return Stream.of(
                Arguments.of("제공자 오류", { providerError }, "UNSUCCESSFUL_RESPONSE", true),
                Arguments.of("응답 없음", { null }, "UNSUCCESSFUL_RESPONSE", true),
                Arguments.of("항목 없음", { page("3", null, 1) }, "COUNT_MISMATCH", false),
                Arguments.of("빈 항목", { page("3", listOf(), 1) }, "COUNT_MISMATCH", false),
                // 1마리 부족은 제공자 불일치로 받아들이므로 2마리 이상 모자란 쪽으로 확인한다.
                Arguments.of("잘린 쪽", { page("3", listOf(one), 1) }, "COUNT_MISMATCH", false),
            )
        }

        @JvmStatic
        fun invalidAnimals(): Stream<Arguments> = Stream.of(
            Arguments.of("번호 없음", animal(null, "20260908", null, "보호중"), "number is missing"),
            Arguments.of("잘못된 갱신 시각", animal("one", "20260908", "bad-date", "보호중"), "timestamp is invalid"),
            Arguments.of(
                "없는 달력 날짜",
                animal("one", "20260908", "2026-02-30 10:00:00", "보호중"),
                "timestamp is invalid",
            ),
        )

        private fun animal(number: String?, intake: String, updated: String?, state: String): ApmsAnimal {
            return ApmsAnimal.builder().desertionNo(number).happenDt(intake).updTm(updated).processState(state).build()
        }

        private fun page(total: String, animals: List<ApmsAnimal>?, number: Int): ApmsRootResponse<ApmsAnimal> {
            return ApmsRootResponse(
                ApmsResponse(
                    ApmsHeader.builder().resultCode("00").build(),
                    ApmsBody(ApmsItems(animals), "1000", number.toString(), total),
                ),
            )
        }
    }
}
