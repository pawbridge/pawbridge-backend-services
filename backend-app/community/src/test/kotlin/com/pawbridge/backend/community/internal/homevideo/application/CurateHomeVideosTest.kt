package com.pawbridge.backend.community.internal.homevideo.application

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CurateHomeVideosTest {
    private val now: Instant = Instant.parse("2026-11-18T00:00:00Z")
    private val collect = mockk<CollectHomeVideoCandidates>()
    private val board = mockk<CurationBoard>()
    private val lookup = mockk<VideoLookup>()
    private lateinit var curation: CurateHomeVideos
    private val applied = slot<BoardChange>()

    @BeforeEach
    fun setup() {
        curation = CurateHomeVideos(collect, board, lookup, Clock.fixed(now, ZoneOffset.UTC))
        every { board.claimWeekly(any(), any(), any()) } returns true
        every { collect.collect() } returns CollectHomeVideoCandidates.Summary(0, 0, 0, 0, 0)
        justRun { board.apply(capture(applied), now) }
        justRun { board.discard(any()) }
        every { board.waiting() } returns emptyList()
    }

    @Test
    fun `주간 교체할 때가 아닐 때__주간 교체하면__후보도 모으지 않고 바꾸지 않는다`() {
        // given
        every { board.claimWeekly(now, Duration.ofDays(7), Duration.ofDays(1)) } returns false

        // when
        val ran = curation.weekly()

        // then
        assertThat(ran).isFalse()
        verify(exactly = 0) { collect.collect() }
        verify(exactly = 0) { board.apply(any(), any()) }
    }

    @Test
    fun `예비 후보가 셋 이상일 때__주간 교체하면__게시 영상 셋을 순위 높은 새 영상 셋으로 같은 자리에 바꾸고 성공을 남긴다`() {
        // given
        val old = listOf(video("OldVideo001", 0, "채널A"), video("OldVideo002", 1, "채널B"), video("OldVideo003", 2, "채널C"))
        every { board.videos() } returns old
        waiting("NewVideo001", "NewVideo002", "NewVideo003", "NewVideo004")
        lookupReturns(meta("NewVideo001", "채널1"), meta("NewVideo002", "채널2"), meta("NewVideo003", "채널3"), meta("NewVideo004", "채널4"))

        // when
        val ran = curation.weekly()

        // then
        assertThat(ran).isTrue()
        assertThat(applied.captured.removed).containsExactlyInAnyOrderElementsOf(old)
        assertThat(applied.captured.added.map { placement -> placement.metadata.videoId to placement.position })
            .containsExactly("NewVideo001" to 0, "NewVideo002" to 1, "NewVideo003" to 2)
        assertThat(applied.captured.weeklySucceeded).isTrue()
    }

    @Test
    fun `주간 교체할 때__후보를 먼저 모은 뒤에 고른다`() {
        // given
        every { board.videos() } returns emptyList()

        // when
        curation.weekly()

        // then
        verifyOrder {
            board.claimWeekly(any(), any(), any())
            collect.collect()
            board.waiting()
        }
    }

    @Test
    fun `후보 모으기가 실패할 때__주간 교체하면__바꾸지 않고 실패를 알린다`() {
        // given
        every { collect.collect() } throws HomeVideoCurationException("판정을 받지 못했습니다(HTTP 503).")

        // when, then
        assertThatThrownBy { curation.weekly() }.isInstanceOf(HomeVideoCurationException::class.java)
        verify(exactly = 0) { board.apply(any(), any()) }
    }

    @Test
    fun `새 영상이 하나뿐일 때__주간 교체하면__가장 오래 걸린 영상 하나만 바꾼다`() {
        // given
        val oldest = video("OldVideo001", 2, "채널A", createdDaysAgo = 30)
        val middle = video("OldVideo002", 0, "채널B", createdDaysAgo = 20)
        val newest = video("OldVideo003", 1, "채널C", createdDaysAgo = 10)
        every { board.videos() } returns listOf(middle, newest, oldest)
        waiting("NewVideo001")
        lookupReturns(meta("NewVideo001", "채널1"))

        // when
        curation.weekly()

        // then
        assertThat(applied.captured.removed).containsExactly(oldest)
        assertThat(applied.captured.added.single().position).isEqualTo(2)
    }

    @Test
    fun `볼 수 없는 게시 영상이 있을 때__주간 교체하면__그 영상부터 뺀다`() {
        // given
        val dead = video("DeadVideo01", 1, "채널A", createdDaysAgo = 1, available = false)
        val oldest = video("OldVideo001", 0, "채널B", createdDaysAgo = 30)
        every { board.videos() } returns listOf(oldest, dead, video("OldVideo002", 2, "채널C"))
        waiting("NewVideo001")
        lookupReturns(meta("NewVideo001", "채널1"))

        // when
        curation.weekly()

        // then
        assertThat(applied.captured.removed).containsExactly(dead)
    }

    @Test
    fun `새 후보가 없을 때__주간 교체하면__아무것도 바꾸지 않고 성공만 남긴다`() {
        // given
        every { board.videos() } returns listOf(video("OldVideo001", 0, "채널A"))

        // when
        curation.weekly()

        // then
        assertThat(applied.captured.removed).isEmpty()
        assertThat(applied.captured.added).isEmpty()
        assertThat(applied.captured.weeklySucceeded).isTrue()
    }

    @Test
    fun `후보끼리 채널이 같을 때__주간 교체하면__채널마다 하나만 고른다`() {
        // given
        every { board.videos() } returns emptyList()
        waiting("NewVideo001", "NewVideo002", "NewVideo003", "NewVideo004")
        lookupReturns(meta("NewVideo001", "같은채널"), meta("NewVideo002", "같은채널"), meta("NewVideo003", "채널3"), meta("NewVideo004", "채널4"))

        // when
        curation.weekly()

        // then
        assertThat(applied.captured.added.map { placement -> placement.metadata.videoId })
            .containsExactly("NewVideo001", "NewVideo003", "NewVideo004")
    }

    @Test
    fun `남기는 영상과 채널이 같은 후보일 때__주간 교체하면__그 후보는 고르지 않는다`() {
        // given
        val kept = video("OldVideo002", 1, "겹치는채널", createdDaysAgo = 1)
        every { board.videos() } returns listOf(video("OldVideo001", 0, "채널A", createdDaysAgo = 30), kept, video("OldVideo003", 2, "채널C", createdDaysAgo = 2))
        waiting("NewVideo001", "NewVideo002")
        lookupReturns(meta("NewVideo001", "채널1"), meta("NewVideo002", "겹치는채널"))

        // when
        curation.weekly()

        // then
        assertThat(applied.captured.added.map { placement -> placement.metadata.videoId }).containsExactly("NewVideo001")
        assertThat(applied.captured.removed.map { video -> video.videoId }).containsExactly("OldVideo001")
    }

    @Test
    fun `재생할 수 없거나 사라진 후보일 때__고르면__건너뛰고 그 후보 기록을 지운다`() {
        // given
        every { board.videos() } returns emptyList()
        waiting("GoneVideo01", "Unplayable1", "NewVideo001")
        lookupReturns(meta("Unplayable1", "채널2", available = false), meta("NewVideo001", "채널1"))

        // when
        curation.weekly()

        // then
        assertThat(applied.captured.added.map { placement -> placement.metadata.videoId }).containsExactly("NewVideo001")
        verify { board.discard(listOf("GoneVideo01", "Unplayable1")) }
    }

    @Test
    fun `이미 홈 영상 표에 있는 후보일 때__고르면__다시 올리지 않는다`() {
        // given
        every { board.videos() } returns listOf(video("NewVideo001", 0, "채널A", published = false))
        waiting("NewVideo001", "NewVideo002")
        lookupReturns(meta("NewVideo002", "채널2"))

        // when
        curation.weekly()

        // then
        verify { lookup.lookup(listOf("NewVideo002")) }
        assertThat(applied.captured.added.map { placement -> placement.metadata.videoId }).containsExactly("NewVideo002")
    }

    @Test
    fun `볼 수 없게 된 게시 영상이 있을 때__빈칸을 채우면__그 자리에 다른 채널의 예비 후보를 올린다`() {
        // given
        val dead = video("DeadVideo01", 1, "채널A", available = false)
        every { board.videos() } returns listOf(video("OldVideo001", 0, "채널B"), dead, video("OldVideo002", 2, "채널C"))
        waiting("SameChann01", "NewVideo001")
        lookupReturns(meta("SameChann01", "채널B"), meta("NewVideo001", "채널1"))

        // when
        val filled = curation.fillGaps()

        // then
        assertThat(filled).isEqualTo(1)
        assertThat(applied.captured.removed).containsExactly(dead)
        assertThat(applied.captured.added.single().metadata.videoId).isEqualTo("NewVideo001")
        assertThat(applied.captured.added.single().position).isEqualTo(1)
        assertThat(applied.captured.weeklySucceeded).isFalse()
    }

    @Test
    fun `게시 영상이 모두 볼 수 있을 때__빈칸을 채우면__후보를 확인하지 않는다`() {
        // given
        every { board.videos() } returns listOf(video("OldVideo001", 0, "채널A"))

        // when
        val filled = curation.fillGaps()

        // then
        assertThat(filled).isZero()
        verify(exactly = 0) { board.waiting() }
        verify(exactly = 0) { board.apply(any(), any()) }
    }

    @Test
    fun `만료로 확인 시각이 지워진 영상만 있을 때__빈칸을 채우면__바꾸지 않는다`() {
        // given
        every { board.videos() } returns listOf(video("OldVideo001", 0, "채널A", available = false, checked = false))

        // when, then
        assertThat(curation.fillGaps()).isZero()
        verify(exactly = 0) { board.apply(any(), any()) }
    }

    @Test
    fun `예비 후보가 없을 때__빈칸을 채우면__그대로 둔다`() {
        // given
        every { board.videos() } returns listOf(video("DeadVideo01", 0, "채널A", available = false))

        // when, then
        assertThat(curation.fillGaps()).isZero()
        verify(exactly = 0) { board.apply(any(), any()) }
    }

    @Test
    fun `일주일 넘게 확인하지 않은 사용 기록이 있을 때__다시 확인하면__남은 영상은 확인 시각을 갱신하고 사라진 영상 기록은 지운다`() {
        // given
        every { board.usedCheckedBefore(now.minus(7, ChronoUnit.DAYS), 50) } returns listOf("UsedVideo01", "GoneVideo01")
        lookupReturns(meta("UsedVideo01", "채널1", available = false))
        justRun { board.confirmUsed(any(), any()) }

        // when
        val checked = curation.recheckUsed()

        // then
        assertThat(checked).isEqualTo(2)
        verify { board.confirmUsed(setOf("UsedVideo01"), now) }
        verify { board.discard(listOf("GoneVideo01")) }
    }

    @Test
    fun `다시 확인할 사용 기록이 없을 때__다시 확인하면__YouTube를 부르지 않는다`() {
        // given
        every { board.usedCheckedBefore(any(), any()) } returns emptyList()

        // when, then
        assertThat(curation.recheckUsed()).isZero()
        verify(exactly = 0) { lookup.lookup(any()) }
    }

    private fun waiting(vararg ids: String) {
        every { board.waiting() } returns ids.mapIndexed { index, id -> WaitingCandidate(id, index) }
    }

    private fun lookupReturns(vararg found: Metadata) {
        every { lookup.lookup(any()) } answers {
            val ids = firstArg<List<String>>()
            found.filter { metadata -> metadata.videoId in ids }.associateBy { metadata -> metadata.videoId }
        }
    }

    private fun meta(id: String, channel: String, available: Boolean = true): Metadata =
        Metadata(id, "제목 $id", channel, "https://i.ytimg.com/vi/$id/hqdefault.jpg", 600, available)

    @Suppress("LongParameterList")
    private fun video(
        id: String,
        position: Int,
        channel: String,
        createdDaysAgo: Long = 10,
        available: Boolean = true,
        published: Boolean = true,
        checked: Boolean = true,
    ): HomeVideo =
        HomeVideo(
            UUID.nameUUIDFromBytes(id.toByteArray()),
            id,
            published,
            position,
            now.minus(createdDaysAgo, ChronoUnit.DAYS),
            "제목 $id",
            channel,
            "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            600,
            available,
            if (checked) now.minusSeconds(60) else null,
        )
}
