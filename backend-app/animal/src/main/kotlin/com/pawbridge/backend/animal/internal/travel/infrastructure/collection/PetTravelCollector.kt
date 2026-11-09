package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executor
import javax.sql.DataSource

/** One bounded collection invocation; never reachable from public GET controllers. */
class PetTravelCollector(
    private val dataSource: DataSource,
    private val catalog: PostgresqlTravelCollectionCatalog,
    private val client: TourApiClient,
    private val properties: TourApiProperties,
    private val clock: Clock,
) {

    @JvmRecord
    data class Result(val status: String, val requests: Int, val discovered: Int, val published: Int)

    private class BudgetExhausted : RuntimeException()

    @Throws(Exception::class)
    fun collect(): Result {
        if (!properties.isEnabled) {
            throw IllegalStateException("TRAVEL_COLLECTION_DISABLED")
        }
        dataSource.connection.use { connection ->
            val acquired: Boolean
            try {
                acquired = ONE == PostgresqlTravelSessionLock.query(connection, LOCK, PostgresqlTravelSessionLock.Operation.ACQUIRE)
            } catch (exception: Exception) {
                connection.abort(DIRECT_EXECUTOR)
                throw IllegalStateException("TRAVEL_LOCK_UNAVAILABLE")
            }
            if (!acquired) {
                throw IllegalStateException("TRAVEL_COLLECTION_BUSY")
            }
            try {
                return collectLocked(connection)
            } finally {
                try {
                    if (ONE != PostgresqlTravelSessionLock.query(connection, LOCK, PostgresqlTravelSessionLock.Operation.RELEASE)) {
                        connection.abort(DIRECT_EXECUTOR)
                    }
                } catch (exception: Exception) {
                    connection.abort(DIRECT_EXECUTOR)
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun collectLocked(connection: Connection): Result {
        val id = UUID.randomUUID().toString()
        catalog.startRun(id, clock.instant())
        val counts = IntArray(3)
        var inventoryCompleted = false
        try {
            val petError = if (properties.isBulkPetEnabled) collectPetPages(connection, counts) else null
            reserve(connection, TourApiClient.Operation.REGIONS, counts)
            val regions = LinkedHashMap<String, String>()
            for (row in client.fetch(TourApiClient.Operation.REGIONS, "")) {
                regions[row["code"]!!] = row["name"]!!
            }
            if (regions.isEmpty()) {
                throw PetTravelException.unavailable(REGIONS_EMPTY, TourApiClient.Operation.REGIONS.name)
            }
            catalog.saveRegions(regions, clock.instant())
            var pages = 0
            while (pages < properties.maxPagesPerRun) {
                val state = catalog.collectionState()
                val shown = if ("HIDDEN" == state.phase) "0" else "1"
                reserve(connection, TourApiClient.Operation.SYNC, counts)
                val page = client.fetchPage(TourApiClient.Operation.SYNC, shown, state.nextPage)
                // An unexpected empty middle page is not evidence of completed inventory.
                val remaining = page.totalCount.toLong() - (state.nextPage - 1).toLong() * 100
                if (page.items.size.toLong() != minOf(100L, maxOf(0L, remaining))) {
                    throw PetTravelException.unavailable(SYNC_PAGE_SIZE, TourApiClient.Operation.SYNC.name)
                }
                for (row in page.items) {
                    if (shown != row["showflag"]) {
                        throw PetTravelException.unavailable(SYNC_SHOWFLAG, TourApiClient.Operation.SYNC.name)
                    }
                    // Never interpret legacy areacode as a legal-district code.
                    val region = row["lDongRegnCd"]
                    catalog.observeBasic(row, if (regions.containsKey(region)) region else null, clock.instant())
                    counts[1]++
                }
                // Advancing only after all rows persist makes interrupted pages safely replayable.
                if (state.nextPage.toLong() * 100 >= page.totalCount) {
                    if ("HIDDEN" == state.phase) {
                        catalog.checkpoint("SHOWN", 1)
                    } else {
                        catalog.beginDetails(clock.instant().minus(Duration.ofDays(properties.detailRefreshDays.toLong())))
                        catalog.completeCycle(clock.instant())
                        inventoryCompleted = true
                        break
                    }
                } else {
                    catalog.checkpoint(state.phase, state.nextPage + 1)
                }
                pages++
            }
            var detailFailures = 0
            for (target in catalog.pending(properties.maxDetailsPerRun)) {
                try {
                    reserve(connection, TourApiClient.Operation.COMMON, counts)
                    val common = client.fetch(TourApiClient.Operation.COMMON, target.contentId)
                    if (common.size != 1) {
                        throw PetTravelException.unavailable()
                    }
                    if (properties.isBulkPetEnabled) {
                        if (catalog.publishCommon(target, common[0], clock.instant())) {
                            counts[2]++
                        }
                    } else {
                        reserve(connection, TourApiClient.Operation.PET, counts)
                        val pet = client.fetch(TourApiClient.Operation.PET, target.contentId)
                        if (pet.size > 1) {
                            throw PetTravelException.unavailable()
                        }
                        if (catalog.publish(target, common[0], if (pet.isEmpty()) emptyMap() else pet[0], clock.instant())) {
                            counts[2]++
                        }
                    }
                } catch (exception: BudgetExhausted) {
                    throw exception
                } catch (exception: Exception) {
                    if (LOCK_LOST == exception.message) {
                        throw exception
                    }
                    catalog.detailFailed(target, clock.instant())
                    detailFailures++
                }
            }
            val visitError = collectVisitDetails(connection, counts)
            val visitPending = catalog.hasPendingVisitDetails()
            if (inventoryCompleted && catalog.pending(1).isEmpty() && !visitPending && petError == null && visitError == null) {
                if (catalog.hasUnresolvedRegions()) {
                    // Keep unresolved rows durable, publish healthy rows, and retry discovery next run.
                    catalog.finishRun(id, "PARTIAL", "REGION_UNRESOLVED", counts[0], counts[1], counts[2], clock.instant())
                    return Result("PARTIAL", counts[0], counts[1], counts[2])
                }
                catalog.finishRun(id, "COMPLETED", null, counts[0], counts[1], counts[2], clock.instant())
                return Result("COMPLETED", counts[0], counts[1], counts[2])
            }
            val error = if (petError != null) petError else if (detailFailures > 0) "DETAIL_FAILED" else visitError
            catalog.finishRun(id, "PARTIAL", error, counts[0], counts[1], counts[2], clock.instant())
            return Result("PARTIAL", counts[0], counts[1], counts[2])
        } catch (exception: BudgetExhausted) {
            catalog.finishRun(id, "QUOTA", "REQUEST_BUDGET", counts[0], counts[1], counts[2], clock.instant())
            return Result("QUOTA", counts[0], counts[1], counts[2])
        } catch (exception: Exception) {
            warnFailure("travel collection failed", exception, counts)
            catalog.finishRun(id, "FAILED", "COLLECTION_FAILED", counts[0], counts[1], counts[2], clock.instant())
            // Do not retain upstream URL/credential-bearing causes in Batch metadata.
            throw IllegalStateException("TRAVEL_COLLECTION_FAILED")
        }
    }

    /** Independent per-operation work queues keep one provider detail failure from blocking the others. */
    @Throws(Exception::class)
    private fun collectVisitDetails(connection: Connection, counts: IntArray): String? {
        var failed = false
        var quota = false
        for (resource in PostgresqlTravelCollectionCatalog.VisitResource.values()) {
            val operation = when (resource) {
                PostgresqlTravelCollectionCatalog.VisitResource.INTRO -> TourApiClient.Operation.INTRO
                PostgresqlTravelCollectionCatalog.VisitResource.INFO -> TourApiClient.Operation.INFO
                PostgresqlTravelCollectionCatalog.VisitResource.IMAGES -> TourApiClient.Operation.IMAGES
            }
            for (target in catalog.pendingVisitDetails(resource, properties.maxVisitDetailsPerRun)) {
                try {
                    reserve(connection, operation, counts)
                    val rows = client.fetchDetail(operation, target.contentId, target.contentTypeId)
                    if (ONE != PostgresqlTravelSessionLock.query(connection, LOCK, PostgresqlTravelSessionLock.Operation.OWNED)) {
                        throw IllegalStateException("TRAVEL_LOCK_LOST")
                    }
                    when (resource) {
                        PostgresqlTravelCollectionCatalog.VisitResource.INTRO -> catalog.saveVisitIntro(target, rows, clock.instant())
                        PostgresqlTravelCollectionCatalog.VisitResource.INFO -> catalog.saveVisitInformation(target, rows, clock.instant())
                        PostgresqlTravelCollectionCatalog.VisitResource.IMAGES -> catalog.saveVisitImages(target, rows, clock.instant())
                    }
                } catch (exception: BudgetExhausted) {
                    quota = true
                    break
                } catch (exception: Exception) {
                    if (LOCK_LOST == exception.message) {
                        throw exception
                    }
                    catalog.visitDetailFailed(resource, target, clock.instant())
                    failed = true
                }
            }
        }
        if (quota) {
            return "VISIT_DETAIL_QUOTA"
        }
        if (failed) {
            return "VISIT_DETAIL_FAILED"
        }
        return if (catalog.hasPendingVisitDetails()) "VISIT_DETAIL_PENDING" else null
    }

    /** Separate provider operation/budget: a failed bulk page never triggers N individual requests. */
    @Throws(Exception::class)
    private fun collectPetPages(connection: Connection, counts: IntArray): String? {
        try {
            var pages = 0
            while (pages < properties.maxBulkPagesPerRun) {
                val state = catalog.petCollectionState()
                if (state.nextPage == 1 && state.completedAt != null &&
                    state.completedAt.plus(Duration.ofDays(1)).isAfter(clock.instant())
                ) {
                    return null
                }
                reserve(connection, TourApiClient.Operation.PET_BULK, counts)
                val startedAt = clock.instant()
                val page = client.fetchPage(TourApiClient.Operation.PET_BULK, "", state.nextPage)
                if (ONE != PostgresqlTravelSessionLock.query(connection, LOCK, PostgresqlTravelSessionLock.Operation.OWNED)) {
                    throw IllegalStateException("TRAVEL_LOCK_LOST")
                }
                catalog.savePetPage(state, page, startedAt, startedAt.plus(Duration.ofDays(properties.detailRefreshDays.toLong())))
                if (state.nextPage.toLong() * 100 >= page.totalCount) {
                    return null
                }
                pages++
            }
            return "PET_BULK_PENDING"
        } catch (exception: BudgetExhausted) {
            catalog.petCollectionFailed("PET_BULK_QUOTA", false)
            return "PET_BULK_QUOTA"
        } catch (exception: Exception) {
            if (LOCK_LOST == exception.message) {
                throw exception
            }
            val changed = "PET_INVENTORY_CHANGED" == exception.message
            warnFailure("travel pet bulk failed", exception, counts)
            catalog.petCollectionFailed(if (changed) "PET_INVENTORY_CHANGED" else "PET_BULK_FAILED", changed)
            return "PET_BULK_FAILED"
        }
    }

    @Throws(Exception::class)
    private fun reserve(connection: Connection, operation: TourApiClient.Operation, counts: IntArray) {
        if (ONE != PostgresqlTravelSessionLock.query(connection, LOCK, PostgresqlTravelSessionLock.Operation.OWNED)) {
            throw IllegalStateException("TRAVEL_LOCK_LOST")
        }
        if (!catalog.reserveRequest(
                operation.name,
                LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))),
                properties.dailyRequestLimit,
            )
        ) {
            throw BudgetExhausted()
        }
        counts[0]++
    }

    /**
     * 실패 원인을 키 없는 값으로만 한 줄 남긴다. 수집 한 번에 단계별로 많아야 한 줄이다.
     * 원인 예외의 메시지에는 서비스 키가 든 요청 URL이 들어갈 수 있어 클래스 이름만 남긴다.
     */
    private fun warnFailure(event: String, failure: Exception, counts: IntArray) {
        val travel = failure as? PetTravelException
        log.warn(
            "[BATCH] {}: kind={}, operation={}, status={}, cause={}, requests={}, discovered={}, published={}",
            event,
            kindOf(failure),
            travel?.operation,
            travel?.status,
            failure.javaClass.simpleName,
            counts[0],
            counts[1],
            counts[2],
        )
    }

    companion object {
        // 수집기가 직접 판단한 실패의 kind 값
        const val REGIONS_EMPTY = "REGIONS_EMPTY"
        const val SYNC_PAGE_SIZE = "SYNC_PAGE_SIZE"
        const val SYNC_SHOWFLAG = "SYNC_SHOWFLAG"
        const val DATABASE = "DATABASE"
        const val OTHER = "OTHER"

        private const val LOCK = "pawbridge_animal.petTravelCollection"
        private const val LOCK_LOST = "TRAVEL_LOCK_LOST"
        private const val ONE = 1

        // Java passed Runnable::run: the abort task runs on the calling thread.
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }

        private val log = LoggerFactory.getLogger(PetTravelCollector::class.java)

        // 우리 코드가 정한 대문자 코드(TRAVEL_LOCK_LOST 같은)만 메시지에서 꺼낸다.
        private val OWN_CODE = Regex("[A-Z][A-Z_]{2,39}")

        private fun kindOf(failure: Exception): String {
            return when {
                failure is PetTravelException -> failure.reason
                failure is SQLException -> DATABASE
                failure is IllegalStateException && failure.message != null && OWN_CODE.matches(failure.message!!) -> failure.message!!
                else -> OTHER
            }
        }
    }
}
