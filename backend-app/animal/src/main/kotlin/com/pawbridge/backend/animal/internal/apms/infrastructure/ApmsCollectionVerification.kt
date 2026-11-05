package com.pawbridge.backend.animal.internal.apms.infrastructure

import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus

/** Original apmsCollectionVerificationStep result policy; Boot preserves the original step name. */
class ApmsCollectionVerification : Tasklet {

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val execution = contribution.stepExecution.jobExecution
        val incomplete = execution.executionContext.getInt(ApmsAnimalSnapshot.INCOMPLETE_COUNT, 0)
        // 제공자의 1마리 불일치는 수집 단계에서 이미 완료로 처리한다. 여기 남은 미완료는 모두 실패다.
        if (incomplete > 0) {
            throw IllegalStateException("APMS collection incomplete; inspect job execution context")
        }
        return RepeatStatus.FINISHED
    }
}
