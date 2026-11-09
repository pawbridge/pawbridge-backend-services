package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

class PetTravelBulkCollectorTest {
    private val source: DataSource = mockk<DataSource>()
    private val catalog: PostgresqlTravelCollectionCatalog = mockk<PostgresqlTravelCollectionCatalog>()
    private val client: TourApiClient = mockk<TourApiClient>()
    private val connection: Connection = mockk<Connection>()
    private val statement: PreparedStatement = mockk<PreparedStatement>()
    private val lockRow: ResultSet = mockk<ResultSet>()
    private val properties = TourApiProperties()
    private val now: Instant = Instant.parse("2026-09-13T00:00:00Z")
    private val collector = PetTravelCollector(source, catalog, client, properties, Clock.fixed(now, ZoneOffset.UTC))
    private val start = PostgresqlTravelCollectionCatalog.PetCollectionState(1, null, "cycle", null)
    private val target =
        PostgresqlTravelCollectionCatalog.Target("123", "11", "20260913000000", true, null, null, 1, true)

    @BeforeEach
    fun setup() {
        properties.isEnabled = true
        properties.isBulkPetEnabled = true
    }

    @Test
    fun `수집이 꺼져 있을 때__수집하면__데이터베이스와 HTTP를 쓰지 않고 거부한다`() {
        // given
        properties.isEnabled = false

        // when
        val failure = catchThrowable { collector.collect() }

        // then
        assertThat(failure).hasMessage("TRAVEL_COLLECTION_DISABLED")
        confirmVerified(source, catalog, client)
    }

    @Test
    fun `다른 세션이 PostgreSQL 잠금을 쥐고 있을 때__수집하면__실행을 시작하지 않고 연결을 닫는다`() {
        // given
        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returns 0
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }

        // when
        val failure = catchThrowable { collector.collect() }

        // then
        assertThat(failure).hasMessage("TRAVEL_COLLECTION_BUSY")
        verify(exactly = 1) { statement.setInt(1, 2) }
        verify(exactly = 1) { statement.queryTimeout = 5 }
        verify(exactly = 1) { connection.close() }
        verify(exactly = 0) { connection.metaData }
        verify(exactly = 0) { connection.abort(any()) }
        confirmVerified(catalog, client)
    }

    @Test
    fun `대량 조회가 실패했을 때__수집하면__개별 반려동물 조회로 대체하지 않고 공통 수집을 이어간다`() {
        // given
        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returns 1
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }

        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { catalog.petCollectionState() } returns start
        justRun { catalog.petCollectionFailed(any(), any()) }
        every { client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1) } throws PetTravelException.unavailable()

        every { client.fetch(TourApiClient.Operation.REGIONS, "") } returns
            listOf(mapOf("code" to "11", "name" to "서울"))
        justRun { catalog.saveRegions(any(), any()) }
        every { catalog.collectionState() } returns
            PostgresqlTravelCollectionCatalog.CollectionState("SHOWN", 1, null, null)
        every { client.fetchPage(TourApiClient.Operation.SYNC, "1", 1) } returns TourApiClient.Page(listOf(), 0)
        justRun { catalog.beginDetails(any()) }
        justRun { catalog.completeCycle(any()) }

        every { catalog.pending(properties.maxDetailsPerRun) } returns listOf(target)
        every { catalog.pending(1) } returns listOf()
        every { client.fetch(TourApiClient.Operation.COMMON, "123") } returns
            listOf(mapOf("contentid" to "123", "title" to "공원"))
        every { catalog.publishCommon(target, any(), now) } returns true
        every { catalog.pendingVisitDetails(any(), any()) } returns listOf()
        every { catalog.hasPendingVisitDetails() } returns false

        // when
        val (result, events) = captureLogs { collector.collect() }

        // then
        assertThat(result.status).isEqualTo("PARTIAL")
        assertThat(result.published).isEqualTo(1)
        assertThat(events.map { event -> event.formattedMessage }).containsExactly(
            "[BATCH] travel pet bulk failed: kind=UNAVAILABLE, operation=null, status=null, cause=PetTravelException, " +
                "requests=1, discovered=0, published=0",
        )
        verify(exactly = 1) { catalog.petCollectionFailed("PET_BULK_FAILED", false) }
        verify(exactly = 0) { client.fetch(TourApiClient.Operation.PET, any()) }
    }

    @Test
    fun `대량 조회의 요청 한도가 소진됐을 때__수집하면__다른 작업의 예산은 계속 쓸 수 있다`() {
        // given
        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returns 1
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }

        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { catalog.petCollectionState() } returns start
        every { catalog.reserveRequest("PET_BULK", any(), any()) } returns false
        justRun { catalog.petCollectionFailed(any(), any()) }

        every { client.fetch(TourApiClient.Operation.REGIONS, "") } returns
            listOf(mapOf("code" to "11", "name" to "서울"))
        justRun { catalog.saveRegions(any(), any()) }
        every { catalog.collectionState() } returns
            PostgresqlTravelCollectionCatalog.CollectionState("SHOWN", 1, null, null)
        every { client.fetchPage(TourApiClient.Operation.SYNC, "1", 1) } returns TourApiClient.Page(listOf(), 0)
        justRun { catalog.beginDetails(any()) }
        justRun { catalog.completeCycle(any()) }

        every { catalog.pending(properties.maxDetailsPerRun) } returns listOf(target)
        every { catalog.pending(1) } returns listOf()
        every { client.fetch(TourApiClient.Operation.COMMON, "123") } returns
            listOf(mapOf("contentid" to "123", "title" to "공원"))
        every { catalog.publishCommon(target, any(), now) } returns true
        every { catalog.pendingVisitDetails(any(), any()) } returns listOf()
        every { catalog.hasPendingVisitDetails() } returns false

        // when
        val result = collector.collect()

        // then
        assertThat(result.published).isEqualTo(1)
        verify(exactly = 0) { client.fetchPage(TourApiClient.Operation.PET_BULK, any(), any()) }
        verify(exactly = 1) { catalog.petCollectionFailed("PET_BULK_QUOTA", false) }
    }

    @Test
    fun `최근에 대량 주기가 완료됐을 때__수집하면__다음 날까지 건너뛴다`() {
        // given
        val completed = PostgresqlTravelCollectionCatalog.PetCollectionState(1, null, "cycle", now.minusSeconds(3600))

        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returns 1
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }

        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { catalog.petCollectionState() } returns completed

        every { client.fetch(TourApiClient.Operation.REGIONS, "") } returns
            listOf(mapOf("code" to "11", "name" to "서울"))
        justRun { catalog.saveRegions(any(), any()) }
        every { catalog.collectionState() } returns
            PostgresqlTravelCollectionCatalog.CollectionState("SHOWN", 1, null, null)
        every { client.fetchPage(TourApiClient.Operation.SYNC, "1", 1) } returns TourApiClient.Page(listOf(), 0)
        justRun { catalog.beginDetails(any()) }
        justRun { catalog.completeCycle(any()) }

        every { catalog.pending(properties.maxDetailsPerRun) } returns listOf(target)
        every { catalog.pending(1) } returns listOf()
        every { client.fetch(TourApiClient.Operation.COMMON, "123") } returns
            listOf(mapOf("contentid" to "123", "title" to "공원"))
        every { catalog.publishCommon(target, any(), now) } returns true
        every { catalog.pendingVisitDetails(any(), any()) } returns listOf()
        every { catalog.hasPendingVisitDetails() } returns false
        every { catalog.hasUnresolvedRegions() } returns false

        // when
        val result = collector.collect()

        // then
        assertThat(result.published).isEqualTo(1)
        verify(exactly = 0) { catalog.reserveRequest("PET_BULK", any(), any()) }
    }

    @Test
    fun `대량 주기가 중단됐을 때__수집하면__최근 완료 이력이 있어도 저장된 페이지부터 재개한다`() {
        // given
        val state = PostgresqlTravelCollectionCatalog.PetCollectionState(2, 101, "cycle", now.minusSeconds(3600))
        val page = TourApiClient.Page(listOf(mapOf("contentid" to "123", "acmpyNeedMtr" to "목줄")), 101)

        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returns 1
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }

        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { catalog.petCollectionState() } returns state
        every { client.fetchPage(TourApiClient.Operation.PET_BULK, "", 2) } returns page
        justRun { catalog.savePetPage(any(), any(), any(), any()) }

        every { client.fetch(TourApiClient.Operation.REGIONS, "") } returns
            listOf(mapOf("code" to "11", "name" to "서울"))
        justRun { catalog.saveRegions(any(), any()) }
        every { catalog.collectionState() } returns
            PostgresqlTravelCollectionCatalog.CollectionState("SHOWN", 1, null, null)
        every { client.fetchPage(TourApiClient.Operation.SYNC, "1", 1) } returns TourApiClient.Page(listOf(), 0)
        justRun { catalog.beginDetails(any()) }
        justRun { catalog.completeCycle(any()) }

        every { catalog.pending(properties.maxDetailsPerRun) } returns listOf(target)
        every { catalog.pending(1) } returns listOf()
        every { client.fetch(TourApiClient.Operation.COMMON, "123") } returns
            listOf(mapOf("contentid" to "123", "title" to "공원"))
        every { catalog.publishCommon(target, any(), now) } returns true
        every { catalog.pendingVisitDetails(any(), any()) } returns listOf()
        every { catalog.hasPendingVisitDetails() } returns false
        every { catalog.hasUnresolvedRegions() } returns false

        // when
        collector.collect()

        // then
        verify(exactly = 1) { catalog.savePetPage(state, page, now, now.plus(Duration.ofDays(14))) }
        verify(exactly = 0) { client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1) }
    }

    @Test
    fun `목록이 바뀌었을 때__수집하면__공통 상세 수집을 멈추지 않고 대량 수집을 다시 시작한다`() {
        // given
        val page = TourApiClient.Page(listOf(), 0)

        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returns 1
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }

        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { catalog.petCollectionState() } returns start
        every { client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1) } returns page
        every { catalog.savePetPage(start, page, any(), any()) } throws IllegalStateException("PET_INVENTORY_CHANGED")
        justRun { catalog.petCollectionFailed(any(), any()) }

        every { client.fetch(TourApiClient.Operation.REGIONS, "") } returns
            listOf(mapOf("code" to "11", "name" to "서울"))
        justRun { catalog.saveRegions(any(), any()) }
        every { catalog.collectionState() } returns
            PostgresqlTravelCollectionCatalog.CollectionState("SHOWN", 1, null, null)
        every { client.fetchPage(TourApiClient.Operation.SYNC, "1", 1) } returns TourApiClient.Page(listOf(), 0)
        justRun { catalog.beginDetails(any()) }
        justRun { catalog.completeCycle(any()) }

        every { catalog.pending(properties.maxDetailsPerRun) } returns listOf(target)
        every { catalog.pending(1) } returns listOf()
        every { client.fetch(TourApiClient.Operation.COMMON, "123") } returns
            listOf(mapOf("contentid" to "123", "title" to "공원"))
        every { catalog.publishCommon(target, any(), now) } returns true
        every { catalog.pendingVisitDetails(any(), any()) } returns listOf()
        every { catalog.hasPendingVisitDetails() } returns false

        // when
        val result = collector.collect()

        // then
        assertThat(result.published).isEqualTo(1)
        verify(exactly = 1) { catalog.petCollectionFailed("PET_INVENTORY_CHANGED", true) }
    }

    @Test
    fun `목록이 커서 페이지 한도에 도달했을 때__수집하면__공통 수집에 차례를 넘긴다`() {
        // given
        properties.maxBulkPagesPerRun = 1
        val page = TourApiClient.Page(
            (1000 until 1100).map { id -> mapOf("contentid" to id.toString()) },
            9686,
        )

        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returns 1
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }

        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { catalog.petCollectionState() } returns start
        every { client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1) } returns page
        justRun { catalog.savePetPage(any(), any(), any(), any()) }

        every { client.fetch(TourApiClient.Operation.REGIONS, "") } returns
            listOf(mapOf("code" to "11", "name" to "서울"))
        justRun { catalog.saveRegions(any(), any()) }
        every { catalog.collectionState() } returns
            PostgresqlTravelCollectionCatalog.CollectionState("SHOWN", 1, null, null)
        every { client.fetchPage(TourApiClient.Operation.SYNC, "1", 1) } returns TourApiClient.Page(listOf(), 0)
        justRun { catalog.beginDetails(any()) }
        justRun { catalog.completeCycle(any()) }

        every { catalog.pending(properties.maxDetailsPerRun) } returns listOf(target)
        every { catalog.pending(1) } returns listOf()
        every { client.fetch(TourApiClient.Operation.COMMON, "123") } returns
            listOf(mapOf("contentid" to "123", "title" to "공원"))
        every { catalog.publishCommon(target, any(), now) } returns true
        every { catalog.pendingVisitDetails(any(), any()) } returns listOf()
        every { catalog.hasPendingVisitDetails() } returns false

        // when
        val result = collector.collect()

        // then
        assertThat(result.status).isEqualTo("PARTIAL")
        assertThat(result.published).isEqualTo(1)
        verify(exactly = 1) { catalog.savePetPage(start, page, any(), any()) }
        verify(exactly = 1) { client.fetchPage(TourApiClient.Operation.PET_BULK, any(), any()) }
        verify(exactly = 1) { catalog.finishRun(any(), "PARTIAL", "PET_BULK_PENDING", any(), any(), 1, now) }
    }

    @Test
    fun `대량 HTTP 중에 잠금을 잃었을 때__응답이 도착하면__커밋도 계속도 하지 않는다`() {
        // given
        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returnsMany listOf(1, 1, 0, 0)
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }
        justRun { connection.abort(any()) }

        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { catalog.petCollectionState() } returns start
        every { client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1) } returns TourApiClient.Page(listOf(), 0)

        // when
        val failure = catchThrowable { collector.collect() }

        // then
        assertThat(failure).hasMessage("TRAVEL_COLLECTION_FAILED").hasNoCause()
        verify(exactly = 0) { catalog.savePetPage(any(), any(), any(), any()) }
        verify(exactly = 0) { client.fetch(TourApiClient.Operation.REGIONS, any()) }
    }

    @Test
    fun `소개 조회가 실패했을 때__방문 자원을 수집하면__정보와 이미지는 계속 게시한다`() {
        // given
        val visit = PostgresqlTravelCollectionCatalog.VisitTarget("123", "12", "20260913000000", 1)
        val info = listOf(mapOf("contentid" to "123", "contenttypeid" to "12", "infoname" to "안내", "infotext" to "설명"))

        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returns 1
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }

        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { catalog.petCollectionState() } returns start
        justRun { catalog.savePetPage(any(), any(), any(), any()) }
        every { client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1) } returns TourApiClient.Page(listOf(), 0)

        every { client.fetch(TourApiClient.Operation.REGIONS, "") } returns
            listOf(mapOf("code" to "11", "name" to "서울"))
        justRun { catalog.saveRegions(any(), any()) }
        every { catalog.collectionState() } returns
            PostgresqlTravelCollectionCatalog.CollectionState("SHOWN", 1, null, null)
        every { client.fetchPage(TourApiClient.Operation.SYNC, "1", 1) } returns TourApiClient.Page(listOf(), 0)
        justRun { catalog.beginDetails(any()) }
        justRun { catalog.completeCycle(any()) }

        every { catalog.pending(properties.maxDetailsPerRun) } returns listOf(target)
        every { catalog.pending(1) } returns listOf()
        every { client.fetch(TourApiClient.Operation.COMMON, "123") } returns
            listOf(mapOf("contentid" to "123", "title" to "공원"))
        every { catalog.publishCommon(target, any(), now) } returns true
        every { catalog.hasPendingVisitDetails() } returns false
        every { catalog.pendingVisitDetails(PostgresqlTravelCollectionCatalog.VisitResource.INTRO, any()) } returns listOf(visit)
        every { catalog.pendingVisitDetails(PostgresqlTravelCollectionCatalog.VisitResource.INFO, any()) } returns listOf(visit)
        every { catalog.pendingVisitDetails(PostgresqlTravelCollectionCatalog.VisitResource.IMAGES, any()) } returns listOf(visit)
        every { client.fetchDetail(TourApiClient.Operation.INTRO, "123", "12") } throws PetTravelException.unavailable()
        every { client.fetchDetail(TourApiClient.Operation.INFO, "123", "12") } returns info
        every { client.fetchDetail(TourApiClient.Operation.IMAGES, "123", "12") } returns listOf()
        justRun { catalog.visitDetailFailed(any(), any(), any()) }
        every { catalog.saveVisitInformation(any(), any(), any()) } returns true
        every { catalog.saveVisitImages(any(), any(), any()) } returns true

        // when
        val result = collector.collect()

        // then
        assertThat(result.status).isEqualTo("PARTIAL")
        verify(exactly = 1) {
            catalog.visitDetailFailed(PostgresqlTravelCollectionCatalog.VisitResource.INTRO, visit, now)
        }
        verify(exactly = 1) { catalog.saveVisitInformation(visit, info, now) }
        verify(exactly = 1) { catalog.saveVisitImages(visit, listOf(), now) }
    }

    @Test
    fun `소개 요청 한도가 소진됐을 때__방문 자원을 수집하면__다른 작업의 예산은 계속 쓴다`() {
        // given
        val visit = PostgresqlTravelCollectionCatalog.VisitTarget("123", "12", "20260913000000", 1)

        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returns 1
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }

        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { catalog.reserveRequest("INTRO", any(), any()) } returns false
        every { catalog.petCollectionState() } returns start
        justRun { catalog.savePetPage(any(), any(), any(), any()) }
        every { client.fetchPage(TourApiClient.Operation.PET_BULK, "", 1) } returns TourApiClient.Page(listOf(), 0)

        every { client.fetch(TourApiClient.Operation.REGIONS, "") } returns
            listOf(mapOf("code" to "11", "name" to "서울"))
        justRun { catalog.saveRegions(any(), any()) }
        every { catalog.collectionState() } returns
            PostgresqlTravelCollectionCatalog.CollectionState("SHOWN", 1, null, null)
        every { client.fetchPage(TourApiClient.Operation.SYNC, "1", 1) } returns TourApiClient.Page(listOf(), 0)
        justRun { catalog.beginDetails(any()) }
        justRun { catalog.completeCycle(any()) }

        every { catalog.pending(properties.maxDetailsPerRun) } returns listOf(target)
        every { catalog.pending(1) } returns listOf()
        every { client.fetch(TourApiClient.Operation.COMMON, "123") } returns
            listOf(mapOf("contentid" to "123", "title" to "공원"))
        every { catalog.publishCommon(target, any(), now) } returns true
        every { catalog.hasPendingVisitDetails() } returns false
        every { catalog.pendingVisitDetails(any(), any()) } returns listOf(visit)
        every { client.fetchDetail(TourApiClient.Operation.INFO, "123", "12") } returns listOf()
        every { client.fetchDetail(TourApiClient.Operation.IMAGES, "123", "12") } returns listOf()
        every { catalog.saveVisitInformation(any(), any(), any()) } returns true
        every { catalog.saveVisitImages(any(), any(), any()) } returns true

        // when
        val result = collector.collect()

        // then
        assertThat(result.status).isEqualTo("PARTIAL")
        verify(exactly = 0) { client.fetchDetail(TourApiClient.Operation.INTRO, "123", "12") }
        verify(exactly = 1) { client.fetchDetail(TourApiClient.Operation.INFO, "123", "12") }
        verify(exactly = 1) { client.fetchDetail(TourApiClient.Operation.IMAGES, "123", "12") }
    }

    @Test
    fun `지역 조회가 HTTP 상태로 실패했을 때__수집하면__키 없는 원인을 WARN 한 줄로 남기고 기존처럼 실패한다`() {
        // given
        properties.isBulkPetEnabled = false
        lockAcquired()
        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { client.fetch(TourApiClient.Operation.REGIONS, "") } throws
            PetTravelException.unavailable(TourApiClient.HTTP_STATUS, "REGIONS", 503)

        // when
        val (failure, events) = captureLogs { catchThrowable { collector.collect() } }

        // then
        assertThat(failure).hasMessage("TRAVEL_COLLECTION_FAILED").hasNoCause()
        assertThat(events).hasSize(1)
        assertThat(events[0].level).isEqualTo(Level.WARN)
        assertThat(events[0].throwableProxy).isNull()
        assertThat(events[0].formattedMessage).isEqualTo(
            "[BATCH] travel collection failed: kind=HTTP_STATUS, operation=REGIONS, status=503, cause=PetTravelException, " +
                "requests=1, discovered=0, published=0",
        )
        verify(exactly = 1) { catalog.finishRun(any(), "FAILED", "COLLECTION_FAILED", 1, 0, 0, now) }
    }

    @Test
    fun `저장소가 실패했을 때__수집하면__메시지 없이 DATABASE 종류로만 남긴다`() {
        // given
        properties.isBulkPetEnabled = false
        lockAcquired()
        justRun { catalog.startRun(any(), any()) }
        justRun { catalog.finishRun(any(), any(), any(), any(), any(), any(), any()) }
        every { catalog.reserveRequest(any(), any(), any()) } returns true
        every { client.fetch(TourApiClient.Operation.REGIONS, "") } returns listOf(mapOf("code" to "11", "name" to "서울"))
        every { catalog.saveRegions(any(), any()) } throws SQLException("synthetic-private serviceKey=secret")

        // when
        val (failure, events) = captureLogs { catchThrowable { collector.collect() } }

        // then
        assertThat(failure).hasMessage("TRAVEL_COLLECTION_FAILED").hasNoCause()
        assertThat(events.map { event -> event.formattedMessage }).containsExactly(
            "[BATCH] travel collection failed: kind=DATABASE, operation=null, status=null, cause=SQLException, " +
                "requests=1, discovered=0, published=0",
        )
    }

    private fun lockAcquired() {
        every { source.connection } returns connection
        every { connection.prepareStatement(any()) } returns statement
        justRun { statement.setInt(any(), any()) }
        justRun { statement.queryTimeout = any() }
        every { statement.executeQuery() } returns lockRow
        every { lockRow.next() } returns true
        every { lockRow.getObject(1) } returns 1
        justRun { lockRow.close() }
        justRun { statement.close() }
        justRun { connection.close() }
    }

    private fun <T> captureLogs(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(PetTravelCollector::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            return block() to appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
