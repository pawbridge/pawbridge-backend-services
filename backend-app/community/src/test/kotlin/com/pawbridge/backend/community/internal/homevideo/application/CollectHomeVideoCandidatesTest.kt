package com.pawbridge.backend.community.internal.homevideo.application

import com.pawbridge.backend.community.internal.homevideo.domain.CandidateStatus
import com.pawbridge.backend.community.internal.homevideo.domain.CandidateVideo
import com.pawbridge.backend.community.internal.homevideo.domain.CurationCriteria
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CollectHomeVideoCandidatesTest {
    private val now: Instant = Instant.parse("2026-11-11T00:00:00Z")
    private val source = mockk<CandidateSource>()
    private val judge = mockk<CandidateJudge>()
    private val store = mockk<CandidateStore>(relaxed = true)
    private val homeVideos = mockk<CurrentHomeVideos>()
    private lateinit var collect: CollectHomeVideoCandidates

    @BeforeEach
    fun setup() {
        collect = CollectHomeVideoCandidates(source, judge, store, homeVideos, Clock.fixed(now, ZoneOffset.UTC))
        every { store.knownIds(any()) } returns emptySet()
        every { homeVideos.videoIds() } returns emptySet()
        every { source.search(any(), any(), any()) } returns emptyList()
        every { source.details(CurationCriteria.REFERENCE_VIDEO_IDS) } returns references()
    }

    @Test
    fun `후보가 판정을 받았을 때__모으면__맞음은 예비로 아님은 탈락으로 검색 순위와 함께 저장한다`() {
        // given
        searchReturns(listOf("Candidate01", "Candidate02"))
        every { source.details(listOf("Candidate01", "Candidate02")) } returns listOf(video("Candidate01"), video("Candidate02"))
        every { judge.judge(any()) } returns referenceVerdicts() + mapOf("Candidate01" to true, "Candidate02" to false)
        val saved = slot<List<JudgedCandidate>>()
        every { store.saveJudged(capture(saved), now) } returns Unit

        // when
        val summary = collect.collect()

        // then
        assertThat(saved.captured).containsExactly(
            JudgedCandidate("Candidate01", "UC-Candidate01", CandidateStatus.WAITING, 0),
            JudgedCandidate("Candidate02", "UC-Candidate02", CandidateStatus.REJECTED, 1),
        )
        assertThat(summary).isEqualTo(CollectHomeVideoCandidates.Summary(2, 2, 2, 1, 1))
    }

    @Test
    fun `모을 때__먼저 29일 지난 기록을 지우고 나서 검색한다`() {
        // when
        collect.collect()

        // then
        verifyOrder {
            store.deleteCheckedBefore(now.minus(29, ChronoUnit.DAYS))
            source.search(any(), any(), any())
        }
    }

    @Test
    fun `검색어마다 결과가 있을 때__모으면__검색어별 1위들 다음 2위들 순서로 합치고 3년 안의 영상만 찾는다`() {
        // given
        val queries = CollectHomeVideoCandidates.QUERIES
        every { source.search(queries[0], any(), 2) } returns listOf("FirstQuery1", "FirstQuery2")
        every { source.search(queries[1], any(), 2) } returns listOf("SecondQuer1", "FirstQuery2")
        every { source.details(any()) } returns emptyList()

        // when
        val summary = collect.collect()

        // then
        verify { source.details(listOf("FirstQuery1", "SecondQuer1", "FirstQuery2")) }
        verify { source.search(queries[0], now.minus(365L * 3, ChronoUnit.DAYS), 2) }
        assertThat(summary.found).isEqualTo(3)
    }

    @Test
    fun `이미 기록했거나 홈 표에 있거나 정답 예시인 영상일 때__모으면__상세도 판정도 다시 하지 않는다`() {
        // given
        val reference = CurationCriteria.REFERENCE_VIDEO_IDS[0]
        searchReturns(listOf("KnownVideo1", "OnBoardVid1", reference, "FreshVideo1"))
        every { store.knownIds(any()) } returns setOf("KnownVideo1")
        every { homeVideos.videoIds() } returns setOf("OnBoardVid1")
        every { source.details(listOf("FreshVideo1")) } returns emptyList()

        // when
        val summary = collect.collect()

        // then
        verify { source.details(listOf("FreshVideo1")) }
        verify(exactly = 0) { judge.judge(any()) }
        assertThat(summary.fresh).isEqualTo(1)
    }

    @Test
    fun `규칙에서 모두 빠졌을 때__모으면__판정을 부르지 않고 저장하지 않는다`() {
        // given
        searchReturns(listOf("ShortVideo1"))
        every { source.details(listOf("ShortVideo1")) } returns listOf(video("ShortVideo1").copy(durationSeconds = 59))

        // when
        collect.collect()

        // then
        verify(exactly = 0) { judge.judge(any()) }
        verify(exactly = 0) { store.saveJudged(any(), any()) }
    }

    @Test
    fun `정답 예시가 판정에서 탈락했을 때__모으면__아무것도 저장하지 않고 실패한다`() {
        // given
        searchReturns(listOf("Candidate01"))
        every { source.details(listOf("Candidate01")) } returns listOf(video("Candidate01"))
        every { judge.judge(any()) } returns referenceVerdicts() +
            mapOf(CurationCriteria.REFERENCE_VIDEO_IDS[1] to false, "Candidate01" to true)

        // when
        val thrown = assertThatThrownBy { collect.collect() }

        // then
        thrown.isInstanceOf(HomeVideoCurationException::class.java)
        verify(exactly = 0) { store.saveJudged(any(), any()) }
    }

    @Test
    fun `정답 예시의 답이 빠졌을 때__모으면__아무것도 저장하지 않고 실패한다`() {
        // given
        searchReturns(listOf("Candidate01"))
        every { source.details(listOf("Candidate01")) } returns listOf(video("Candidate01"))
        every { judge.judge(any()) } returns mapOf("Candidate01" to true)

        // when, then
        assertThatThrownBy { collect.collect() }.isInstanceOf(HomeVideoCurationException::class.java)
        verify(exactly = 0) { store.saveJudged(any(), any()) }
    }

    @Test
    fun `정답 예시를 YouTube에서 하나도 찾지 못했을 때__모으면__판정 없이 실패한다`() {
        // given
        searchReturns(listOf("Candidate01"))
        every { source.details(listOf("Candidate01")) } returns listOf(video("Candidate01"))
        every { source.details(CurationCriteria.REFERENCE_VIDEO_IDS) } returns emptyList()

        // when, then
        assertThatThrownBy { collect.collect() }.isInstanceOf(HomeVideoCurationException::class.java)
        verify(exactly = 0) { judge.judge(any()) }
    }

    @Test
    fun `판정이 실패할 때__모으면__저장하지 않고 실패를 그대로 알린다`() {
        // given
        searchReturns(listOf("Candidate01"))
        every { source.details(listOf("Candidate01")) } returns listOf(video("Candidate01"))
        every { judge.judge(any()) } throws HomeVideoCurationException("판정을 받지 못했습니다(HTTP 503).")

        // when, then
        assertThatThrownBy { collect.collect() }.isInstanceOf(HomeVideoCurationException::class.java)
        verify(exactly = 0) { store.saveJudged(any(), any()) }
    }

    @Test
    fun `판정 답이 빠진 후보가 있을 때__모으면__그 후보만 저장하지 않는다`() {
        // given
        searchReturns(listOf("Candidate01", "Candidate02"))
        every { source.details(any()) } answers {
            val ids = firstArg<List<String>>()
            if (ids == CurationCriteria.REFERENCE_VIDEO_IDS) references() else ids.map { id -> video(id) }
        }
        every { judge.judge(any()) } returns referenceVerdicts() + mapOf("Candidate02" to true)
        val saved = slot<List<JudgedCandidate>>()
        every { store.saveJudged(capture(saved), now) } returns Unit

        // when
        collect.collect()

        // then
        assertThat(saved.captured.map { candidate -> candidate.videoId }).containsExactly("Candidate02")
    }

    @Test
    fun `후보가 40개를 넘을 때__모으면__40개씩 나눠 묶음마다 정답 예시를 섞어 판정한다`() {
        // given
        val ids = (1..45).map { number -> "Candidat" + number.toString().padStart(3, '0') }
        searchReturns(ids)
        every { source.details(ids) } returns ids.map { id -> video(id) }
        val batches = ArrayList<List<CandidateVideo>>()
        every { judge.judge(capture(batches)) } answers {
            firstArg<List<CandidateVideo>>().associate { video -> video.videoId to true }
        }

        // when
        collect.collect()

        // then
        assertThat(batches.map { batch -> batch.size }).containsExactly(43, 8)
        for (batch in batches) {
            assertThat(batch.map { video -> video.videoId }).containsAll(CurationCriteria.REFERENCE_VIDEO_IDS)
        }
    }

    private fun searchReturns(ids: List<String>) {
        every { source.search(CollectHomeVideoCandidates.QUERIES[0], any(), any()) } returns ids
    }

    private fun references(): List<CandidateVideo> =
        CurationCriteria.REFERENCE_VIDEO_IDS.map { id -> video(id) }

    private fun referenceVerdicts(): Map<String, Boolean> =
        CurationCriteria.REFERENCE_VIDEO_IDS.associateWith { true }

    private fun video(id: String): CandidateVideo =
        CandidateVideo(
            id,
            "UC-$id",
            "개인 채널",
            "유기견 입양 브이로그",
            "첫 산책",
            600,
            5_000,
            now.minus(30, ChronoUnit.DAYS),
            true,
        )
}
