package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.explore.JobExplorer
import java.time.Clock
import java.time.LocalDate
import java.util.TreeMap

class ApmsSyncPlanFactory(
    private val animals: AnimalRepository,
    private val explorer: JobExplorer,
    private val clock: Clock,
) {

    fun create(jobName: String): ApmsSyncPlan {
        // 실행 기록이 쌓일수록 계획 생성이 느려지는지 보기 위한 측정이다.
        val started = System.nanoTime()
        val today = LocalDate.now(clock)
        val recent = today.minusDays(30)
        val oldest = animals.findEarliestHappenDate(ApiSource.APMS_ANIMAL)
        var history = if (oldest != null && oldest.isBefore(recent)) oldest else recent
        val historyStarted = System.nanoTime()
        val prior = history(jobName, today)
        val historyMillis = elapsedMillis(historyStarted)
        var updated = earlier(prior.recovery, recent)!!
        if (updated.isBefore(history)) {
            history = updated
        }
        val monthly: MutableMap<LocalDate, LocalDate> = TreeMap()
        var intake = recent
        // Failed attempts also preserve the unfiltered intake window (animals may have no updTm).
        for (attempt in prior.attempts) {
            history = earlier(attempt.plan.historyStart, history)!!
            intake = earlier(attempt.plan.recentStart, intake)!!
        }
        for (attempt in prior.attempts) {
            val results = ApmsQueryProgress.verifiedResults(attempt.execution, attempt.plan)
            val queries = attempt.plan.queries()
            for (i in queries.indices) {
                val query = queries[i]
                val complete = results.isNotEmpty() && results[i].complete
                if (query.updatedStart == null) {
                    if (!complete) {
                        intake = earlier(intake, query.intakeStart)!!
                    } else if (!query.intakeStart.isAfter(intake) && query.intakeEnd.isAfter(intake)) {
                        intake = query.intakeEnd
                    }
                } else {
                    val checkpoint = monthly.getOrDefault(query.intakeStart, updated)
                    // An incomplete overlap can contain delayed source records, even before a prior success.
                    if (!complete) {
                        monthly[query.intakeStart] = earlier(checkpoint, query.updatedStart)!!
                    } else if (!query.updatedStart.isAfter(checkpoint) && query.updatedEnd!!.isAfter(checkpoint)) {
                        // A later narrow window cannot fill an earlier unresolved gap.
                        monthly[query.intakeStart] = query.updatedEnd
                    }
                }
            }
        }
        monthly.replaceAll { _, through -> earlier(through, recent)!! }
        for (start in monthly.values) {
            updated = earlier(updated, start)!!
        }
        history = earlier(updated, history)!!
        val plan = ApmsSyncPlan(earlier(intake, recent), history.withDayOfMonth(1), updated, today, monthly)
        log.info(
            "[BATCH] APMS plan created: elapsedMs={}, historyMs={}, instancesRead={}, executionsRead={}, retainedAttempts={}",
            elapsedMillis(started),
            historyMillis,
            prior.instancesRead,
            prior.executionsRead,
            prior.attempts.size,
        )
        return plan
    }

    private fun history(jobName: String, today: LocalDate): History {
        var firstAttempt: LocalDate? = null
        val attempts = ArrayList<Attempt>()
        var offset = 0
        var instancesRead = 0
        var executionsRead = 0
        while (true) {
            val instances = explorer.getJobInstances(jobName, offset, 100)
            if (instances.isEmpty()) {
                break
            }
            for (instance in instances) {
                instancesRead++
                val executions = ArrayList(explorer.getJobExecutions(instance))
                executionsRead += executions.size
                executions.sortWith(Comparator.comparing<JobExecution, Long> { execution -> execution.id }.reversed())
                for (execution in executions) {
                    val contract = execution.jobParameters.getString(ApmsSyncPlan.CONTRACT_KEY)
                    if (ApmsSyncPlan.CONTRACT != contract && ApmsSyncPlan.LEGACY_CONTRACT != contract) {
                        continue
                    }
                    val plan = ApmsSyncPlan.from(execution.jobParameters)
                    if (plan.end.isAfter(today)) {
                        throw IllegalStateException("APMS recovery window is in the future")
                    }
                    if (successful(execution, plan, contract)) {
                        attempts.sortWith(Comparator.comparing<Attempt, Long> { attempt -> attempt.execution.id })
                        return History(plan.end, java.util.List.copyOf(attempts), instancesRead, executionsRead)
                    }
                    firstAttempt = earlier(plan.updatedStart, firstAttempt)
                    attempts.add(Attempt(execution, plan))
                }
            }
            offset += instances.size
        }
        attempts.sortWith(Comparator.comparing<Attempt, Long> { attempt -> attempt.execution.id })
        return History(firstAttempt, java.util.List.copyOf(attempts), instancesRead, executionsRead)
    }

    private fun successful(execution: JobExecution, plan: ApmsSyncPlan, contract: String?): Boolean {
        if (execution.status != BatchStatus.COMPLETED ||
            execution.stepExecutions.any { step -> step.skipCount != 0L }
        ) {
            return false
        }
        // The older contract has no per-query evidence. Retain its existing full-success fallback.
        if (ApmsSyncPlan.LEGACY_CONTRACT == contract) {
            return true
        }
        val results = ApmsQueryProgress.verifiedResults(execution, plan)
        return results.isNotEmpty() && results.all { result -> result.complete }
    }

    private class Attempt(val execution: JobExecution, val plan: ApmsSyncPlan)

    private class History(
        val recovery: LocalDate?,
        val attempts: List<Attempt>,
        val instancesRead: Int,
        val executionsRead: Int,
    )

    private companion object {
        private val log = LoggerFactory.getLogger(ApmsSyncPlanFactory::class.java)

        private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000

        private fun earlier(left: LocalDate?, right: LocalDate?): LocalDate? {
            if (left == null) {
                return right
            }
            return if (right == null || left.isBefore(right)) left else right
        }
    }
}
