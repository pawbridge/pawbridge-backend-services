package com.pawbridge.backend.animal.internal.apms.infrastructure

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.repeat.RepeatStatus

class ApmsCollectionVerificationTest {
    @Test
    fun `미완료 구간이 없을 때__검증하면__끝낸다`() {
        // given
        val contribution = contribution(0, 0)
        val chunkContext = mockk<ChunkContext>()

        // when
        val status = ApmsCollectionVerification().execute(contribution, chunkContext)

        // then
        assertThat(status).isEqualTo(RepeatStatus.FINISHED)
    }

    @ParameterizedTest
    @CsvSource("1,0", "1,1", "3,1", "3,3")
    fun `미완료 구간이 남았을 때__건수 불일치만 있어도__불완전한 실행을 거부한다`(incomplete: Int, mismatches: Int) {
        // given
        val contribution = contribution(incomplete, mismatches)
        val chunkContext = mockk<ChunkContext>()

        // when
        val thrown = assertThatThrownBy { ApmsCollectionVerification().execute(contribution, chunkContext) }

        // then
        thrown
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("APMS collection incomplete; inspect job execution context")
    }

    private fun contribution(incomplete: Int, mismatches: Int): StepContribution {
        val execution = JobExecution(1L)
        execution.executionContext.putInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, incomplete)
        execution.executionContext.putInt(ApmsAnimalSnapshot.COUNT_MISMATCH_COUNT, mismatches)
        return StepContribution(StepExecution("apmsCollectionVerificationStep", execution))
    }
}
