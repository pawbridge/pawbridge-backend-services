package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import org.slf4j.LoggerFactory
import org.springframework.batch.core.JobExecution
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Fetched once before any animal writes; shared with shelter preparation in the same job.
 *
 * Boot가 @JobScope 빈으로 등록하므로(CGLIB 프록시) 클래스와 `animals()`는 open이어야 한다.
 */
open class ApmsAnimalSnapshot(
    private val client: ApmsApiClient,
    private val serviceKey: String?,
    private val execution: JobExecution,
    maxRequests: Int,
    maxAnimals: Int,
) {
    private val plan: ApmsSyncPlan = ApmsSyncPlan.from(execution.jobParameters)
    private val maxRequests: Int
    private val maxAnimals: Int
    private var requests: Int = 0
    private var pageRetries: Int = 0
    private var animals: List<ApmsAnimal>? = null

    init {
        if (maxRequests < 1 || maxAnimals < 1) {
            throw IllegalArgumentException("APMS collection limits must be positive")
        }
        this.maxRequests = maxRequests
        this.maxAnimals = maxAnimals
    }

    @Synchronized
    open fun animals(): List<ApmsAnimal> {
        val collected = animals
        if (collected != null) {
            return collected
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw IllegalStateException("APMS external fetch must run outside the writer transaction")
        }
        val byNumber = LinkedHashMap<String, ApmsAnimal>()
        val incomplete = ArrayList<String>()
        var countMismatches = 0
        val providerGaps = ArrayList<String>()
        val results = ArrayList<ApmsQueryProgress.Result>()
        // Complete the first pass before spending retries on a problematic interval.
        // A bad early month must not consume the request budget of later healthy months.
        val initial = LinkedHashMap<ApmsSyncPlan.Query, Outcome>()
        val queries = plan.queries()
        for ((index, query) in queries.withIndex()) {
            // 쪽 재시도가 뒤 조회의 첫 요청을 빼앗지 않도록 아직 받지 않은 조회 수만큼 요청을 남겨 둔다.
            val untouched = queries.size - index - 1
            initial[query] = scan(query, byNumber, RecoveryBudget(Int.MAX_VALUE), untouched)
        }
        for (entry in initial.entries) {
            val query = entry.key
            var result = entry.value
            if (!result.complete) {
                result = recover(query, result, 0, RecoveryBudget(RECOVERY_REQUESTS_PER_QUERY), byNumber)
            }
            results.add(ApmsQueryProgress.Result(query, result.complete))
            if (result.complete && result.providerGap) {
                providerGaps.add(
                    "intake=" + query.intakeStart + ".." + query.intakeEnd +
                        ";updated=" + query.updatedStart + ".." + query.updatedEnd +
                        ";reported=" + result.expected + ";observed=" + result.numbers.size,
                )
            }
            if (!result.complete) {
                if ("COUNT_MISMATCH" == result.reason) {
                    countMismatches++
                }
                incomplete.add(
                    "intake=" + query.intakeStart + ".." + query.intakeEnd +
                        ";updated=" + query.updatedStart + ".." + query.updatedEnd +
                        ";reason=" + result.reason +
                        (if (result.firstReason != null) ";firstReason=" + result.firstReason else "") +
                        ";reported=" + result.expected +
                        ";observed=" + result.numbers.size,
                )
            }
        }
        ApmsQueryProgress.store(execution, results)
        val snapshot: List<ApmsAnimal> = java.util.List.copyOf(byNumber.values)
        animals = snapshot
        execution.executionContext.putInt(INCOMPLETE_COUNT, incomplete.size)
        execution.executionContext.putString(INCOMPLETE_DETAILS, incomplete.joinToString("\n"))
        execution.executionContext.putInt(COUNT_MISMATCH_COUNT, countMismatches)
        execution.executionContext.putInt(COLLECTED_COUNT, snapshot.size)
        execution.executionContext.putInt(PROVIDER_GAP_COUNT, providerGaps.size)
        execution.executionContext.putString(PROVIDER_GAP_DETAILS, providerGaps.joinToString("\n"))
        if (providerGaps.isNotEmpty()) {
            log.warn("[BATCH] APMS provider reported one more animal than it returned: {}", providerGaps.joinToString(" | "))
        }
        log.info(
            "APMS snapshot ready: requests={}, pageRetries={}, animals={}, incompleteQueries={}, updatedStart={}, end={}",
            requests,
            pageRetries,
            snapshot.size,
            incomplete.size,
            plan.updatedStart,
            plan.end,
        )
        return snapshot
    }

    private fun recover(
        query: ApmsSyncPlan.Query,
        first: Outcome,
        depth: Int,
        budget: RecoveryBudget,
        byNumber: MutableMap<String, ApmsAnimal>,
    ): Outcome {
        val observed = HashSet(first.numbers)
        var expected = first.expected
        var previous = first
        // 끝내 못 채운 이유는 마지막 시도의 사유로 남기므로 시도마다 사유를 차례로 모은다.
        val attempts = ArrayList<String?>()
        var retry = scan(query, byNumber, budget)
        attempts.add(attemptReason(retry))
        observed.addAll(retry.numbers)
        expected = Math.max(expected, retry.expected)
        if (fills(retry, expected, observed)) {
            return retry
        }
        // 첫 조회가 중간에 끊겨 비교할 결과가 없으면 한 번 더 받아 연속된 두 결과를 맞춘다.
        if (!first.providerGap && retry.providerGap) {
            previous = retry
            retry = scan(query, byNumber, budget)
            attempts.add(attemptReason(retry))
            observed.addAll(retry.numbers)
            expected = Math.max(expected, retry.expected)
            if (fills(retry, expected, observed)) {
                return retry
            }
        }
        val days = ChronoUnit.DAYS.between(query.intakeStart, query.intakeEnd)
        if (depth < MAX_SPLIT_DEPTH && days > 0 && budget.remaining > 0 && requests < maxRequests) {
            val middle = query.intakeStart.plusDays(days / 2)
            val leftQuery = ApmsSyncPlan.Query(query.intakeStart, middle, query.updatedStart, query.updatedEnd)
            val rightQuery = ApmsSyncPlan.Query(middle.plusDays(1), query.intakeEnd, query.updatedStart, query.updatedEnd)
            var left = scan(leftQuery, byNumber, budget)
            if (!left.complete) {
                left = recover(leftQuery, left, depth + 1, budget, byNumber)
            }
            var right = scan(rightQuery, byNumber, budget)
            if (!right.complete) {
                right = recover(rightQuery, right, depth + 1, budget, byNumber)
            }
            val children = HashSet(left.numbers)
            children.addAll(right.numbers)
            val reconciled = left.complete && right.complete &&
                children.size.toLong() == left.expected + right.expected &&
                children.size >= expected && children.containsAll(observed)
            observed.addAll(children)
            if (reconciled) {
                return Outcome(true, children.size.toLong(), children, "")
            }
            if (!left.complete) {
                attempts.add(left.reason)
            }
            if (!right.complete) {
                attempts.add(right.reason)
            }
            // 두 하위 구간이 모두 채워졌는데 상위 보고와 맞지 않으면 건수 불일치다.
            if (left.complete && right.complete) {
                attempts.add(COUNT_MISMATCH)
            }
        }
        // 다시 받고 나눠 받아도 같은 1마리가 계속 빠지면 제공자가 보고 건수를 잘못 준 것으로 보고 받은 그대로 완료한다.
        if (sameProviderGap(previous, retry, expected, observed)) {
            return Outcome(true, retry.expected, retry.numbers, "", true)
        }
        val reason = lastCause(attempts)
        val firstReason = if (first.reason != reason) first.reason else null
        return Outcome(false, expected, observed, reason, firstReason = firstReason)
    }

    /** 다시 받은 결과가 스스로는 완료여도 앞서 본 건수나 번호를 채우지 못했으면 건수 불일치다. */
    private fun attemptReason(attempt: Outcome): String? = if (attempt.complete) COUNT_MISMATCH else attempt.reason

    /**
     * 마지막 시도의 사유를 고른다. 첫 사유만 남기면 2마리 부족(COUNT_MISMATCH)으로 시작해 다시 받는 중
     * 시간 초과로 끝난 경우가 COUNT_MISMATCH로만 보여 원인을 잘못 읽는다.
     * 요청 예산이 떨어져 멈춘 시도(REQUEST_BUDGET)는 우리 쪽 한도라서, 그 앞에 제공자 쪽 사유가 있으면 그것을 쓴다.
     */
    private fun lastCause(attempts: List<String?>): String? {
        return attempts.lastOrNull { reason -> reason != REQUEST_BUDGET } ?: attempts.lastOrNull()
    }

    private fun fills(retry: Outcome, expected: Long, observed: Set<String>): Boolean {
        return retry.complete && retry.expected >= expected && retry.numbers.containsAll(observed)
    }

    private fun sameProviderGap(previous: Outcome, retry: Outcome, expected: Long, observed: Set<String>): Boolean {
        return previous.providerGap && retry.providerGap &&
            previous.expected == retry.expected && retry.expected == expected &&
            previous.numbers == retry.numbers && retry.numbers.containsAll(observed)
    }

    /** [reserve]는 이 조회의 쪽 재시도에 쓰지 않고 남겨 둘 요청 수다(첫 회차에서 아직 받지 않은 조회 수). */
    private fun scan(
        query: ApmsSyncPlan.Query,
        byNumber: MutableMap<String, ApmsAnimal>,
        budget: RecoveryBudget,
        reserve: Int = 0,
    ): Outcome {
        val numbers = HashSet<String>()
        var total = -1L
        var consistent = true
        var page = 1
        while (true) {
            if (requests >= maxRequests || budget.remaining <= 0) {
                return Outcome(false, Math.max(0L, total), numbers, "REQUEST_BUDGET")
            }
            requests++
            budget.remaining--
            val response: ApmsPage
            try {
                response = fetchPage(query, page, budget, reserve)
            } catch (exception: ApmsPage.FetchException) {
                return Outcome(false, Math.max(0L, total), numbers, exception.message)
            }
            if (total < 0) {
                total = response.total
            }
            // 이 쪽을 받기 전까지 모든 쪽이 꽉 차고 합계가 같았는지, 이 쪽에서 합계나 중복이 어긋났는지 따로 본다.
            val earlierPagesConsistent = consistent
            var pageStable = response.total == total
            consistent = consistent && response.total == total && response.countMatches
            total = Math.max(total, response.total)
            for (item in response.items) {
                validate(item, query)
                val desertionNo = item.desertionNo!!
                // add()는 부수효과가 있으므로 consistent가 이미 false여도 반드시 실행한다(Java의 &=).
                val added = numbers.add(desertionNo)
                consistent = consistent && added
                pageStable = pageStable && added
                if (!byNumber.containsKey(desertionNo) && byNumber.size >= maxAnimals) {
                    return Outcome(false, total, numbers, "ANIMAL_BUDGET")
                }
                byNumber.merge(desertionNo, item) { left, right -> newest(left, right) }
            }
            if (response.last || response.items.isEmpty() || !consistent) {
                val complete = consistent && numbers.size.toLong() == total
                // 앞쪽은 모두 정상이고 마지막 쪽에서만 정확히 1마리 모자란 경우만 제공자 불일치 후보로 본다.
                val providerGap = !complete && earlierPagesConsistent && pageStable && response.last &&
                    response.items.isNotEmpty() && numbers.size.toLong() == total - 1
                return Outcome(complete, total, numbers, if (complete) "" else "COUNT_MISMATCH", providerGap)
            }
            page++
        }
    }

    /**
     * 요청이 끊기면(REQUEST_FAILED: 클라이언트가 던진 실패, 곧 시간 초과, 연결 실패, HTTP 오류, 본문 해석 실패)
     * 같은 쪽을 바로 다시 받는다.
     * 지금까지는 한 쪽만 끊겨도 그 조회를 1쪽부터 다시 받았다. 같은 쪽을 다시 받아도 쪽 사이 일관성 검사는
     * [scan]이 그대로 하므로, 쪽을 늦게 받은 것과 같다.
     *
     * 끊긴 요청은 이미 제한 시간(최대 15초)을 기다린 뒤라 따로 쉬지 않는다.
     * 제공자가 정상 응답으로 거절한 경우(UNSUCCESSFUL_RESPONSE, INVALID_PAGINATION)는 같은 쪽을 다시 받아도
     * 나아지기 어려워 기존처럼 조회 단위 복구에 맡긴다.
     */
    private fun fetchPage(query: ApmsSyncPlan.Query, page: Int, budget: RecoveryBudget, reserve: Int): ApmsPage {
        var retries = 0
        while (true) {
            try {
                return ApmsPage.fetch(client, serviceKey, page, 1000, query)
            } catch (exception: ApmsPage.FetchException) {
                if (!canRetryPage(exception, retries, budget, reserve)) {
                    throw exception
                }
                retries++
                pageRetries++
                requests++
                budget.remaining--
            }
        }
    }

    private fun canRetryPage(
        exception: ApmsPage.FetchException,
        retries: Int,
        budget: RecoveryBudget,
        reserve: Int,
    ): Boolean {
        return REQUEST_FAILED == exception.message &&
            retries < PAGE_RETRIES &&
            // 작업 중단으로 끊긴 요청은 다시 보내지 않는다(클라이언트가 인터럽트 표시를 되살려 둔다).
            !Thread.currentThread().isInterrupted &&
            budget.remaining > 0 &&
            maxRequests - requests > reserve
    }

    private fun validate(item: ApmsAnimal, query: ApmsSyncPlan.Query) {
        if (item.desertionNo == null || isBlank(item.desertionNo!!)) {
            throw IllegalStateException("APMS animal number is missing")
        }
        item.updTm = ApmsUpdatedAt.normalize(item.updTm)
        if (query.updatedStart != null) {
            val updated = ApmsUpdatedAt.parse(item.updTm)
            if (updated == null ||
                updated.toLocalDate().isBefore(query.updatedStart) ||
                updated.toLocalDate().isAfter(query.updatedEnd)
            ) {
                throw IllegalStateException("APMS result is outside the requested update window")
            }
        }
        if (item.happenDt == null || isBlank(item.happenDt!!)) {
            throw IllegalStateException("APMS intake date is missing")
        }
        val intake: LocalDate
        try {
            intake = LocalDate.parse(item.happenDt, DateTimeFormatter.BASIC_ISO_DATE)
        } catch (exception: RuntimeException) {
            throw IllegalStateException("APMS intake date is invalid")
        }
        if (intake.isBefore(query.intakeStart) || intake.isAfter(query.intakeEnd)) {
            throw IllegalStateException("APMS result is outside the requested intake window")
        }
    }

    private class Outcome(
        val complete: Boolean,
        val expected: Long,
        val numbers: Set<String>,
        val reason: String?,
        /** 미완료면 "마지막 쪽에서만 1마리 부족", 완료면 그 상태가 두 번 같게 나와 받아들였다는 뜻이다. */
        val providerGap: Boolean = false,
        /** 복구 끝에 미완료로 남았고 첫 조회의 사유가 마지막 사유([reason])와 다를 때만 첫 사유를 담는다. */
        val firstReason: String? = null,
    )

    private class RecoveryBudget(var remaining: Int)

    private fun newest(left: ApmsAnimal, right: ApmsAnimal): ApmsAnimal {
        if (left == right) {
            return left
        }
        val leftTime = ApmsUpdatedAt.parse(left.updTm)
        val rightTime = ApmsUpdatedAt.parse(right.updTm)
        if (leftTime == null && rightTime != null) {
            return right
        }
        if (rightTime == null && leftTime != null) {
            return left
        }
        if (leftTime != null && leftTime != rightTime) {
            return if (leftTime.isAfter(rightTime)) left else right
        }
        throw IllegalStateException("APMS duplicate animal has conflicting data at the same update time")
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    companion object {
        private val log = LoggerFactory.getLogger(ApmsAnimalSnapshot::class.java)

        const val INCOMPLETE_COUNT: String = "apms.sync.incompleteQueryCount"
        const val INCOMPLETE_DETAILS: String = "apms.sync.incompleteQueryDetails"
        const val COUNT_MISMATCH_COUNT: String = "apms.sync.countMismatchQueryCount"
        const val COLLECTED_COUNT: String = "apms.sync.collectedCount"
        const val PROVIDER_GAP_COUNT: String = "apms.sync.providerCountGapQueryCount"
        const val PROVIDER_GAP_DETAILS: String = "apms.sync.providerCountGaps"
        private const val MAX_SPLIT_DEPTH = 2
        private const val RECOVERY_REQUESTS_PER_QUERY = 8

        /** 끊긴 쪽 하나를 다시 받는 최대 횟수. 처음 요청까지 한 쪽에 최대 3번 보낸다. */
        private const val PAGE_RETRIES = 2
        private const val REQUEST_FAILED = "REQUEST_FAILED"
        private const val REQUEST_BUDGET = "REQUEST_BUDGET"
        private const val COUNT_MISMATCH = "COUNT_MISMATCH"
    }
}
