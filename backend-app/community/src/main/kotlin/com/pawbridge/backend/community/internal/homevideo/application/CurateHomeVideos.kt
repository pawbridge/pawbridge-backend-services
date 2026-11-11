package com.pawbridge.backend.community.internal.homevideo.application

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import java.time.Clock
import java.time.Duration

/**
 * 홈 영상 자동 교체. 1시간마다 도는 갱신 작업이 순서대로 부른다.
 *
 * - [fillGaps]: 볼 수 없게 된 게시 영상을 예비 후보로 바꾼다(검색, 판정 없음).
 * - [recheckUsed]: 홈에 올렸던 영상 기록을 30일이 되기 전에 YouTube에서 다시 확인한다(약관).
 * - [weekly]: 일주일에 한 번 후보를 모으고 게시 영상 3개를 바꾼다. 실패하면 하루 뒤 다시 시도한다.
 *
 * 자동, 수동 구분은 두지 않는다: 관리자가 올린 영상도 똑같이 오래된 순서로 바뀐다.
 */
class CurateHomeVideos(
    private val collect: CollectHomeVideoCandidates,
    private val board: CurationBoard,
    private val lookup: VideoLookup,
    private val clock: Clock,
) {

    /** 주간 교체할 때가 아니면 false. 교체했으면(새 후보가 없어 바꾼 게 없어도) true. */
    fun weekly(): Boolean {
        val now = clock.instant()
        if (!board.claimWeekly(now, WEEK, DAY)) {
            return false
        }
        collect.collect()

        val videos = board.videos()
        val published = videos.filter { video -> video.published }
        // 볼 수 없는 영상을 가장 먼저, 그다음 오래 걸린 영상부터 뺀다.
        val removalOrder = published.sortedWith(compareBy({ video -> video.available }, { video -> video.createdAt }))
        var picks = choose(SLOTS, emptySet(), videos)
        var removed = removalOrder.take(maxOf(0, published.size + picks.size - SLOTS))
        // 새 영상이 모자라 남기는 영상이 있으면, 남는 영상과 채널이 겹치는 새 영상은 뺀다(한 화면에 같은 채널 1개).
        while (true) {
            val keptChannels = (published - removed.toSet()).mapNotNull { video -> video.channelTitle }.toSet()
            val clashing = picks.filter { pick -> pick.channelTitle in keptChannels }
            if (clashing.isEmpty()) {
                break
            }
            picks = picks - clashing.toSet()
            removed = removalOrder.take(maxOf(0, published.size + picks.size - SLOTS))
        }
        val placements = place(picks, removed, videos)
        board.apply(BoardChange(removed, placements, true), now)
        return true
    }

    /** 바꾼 영상 수. 예비 후보가 없으면 빈칸은 그대로 둔다(홈은 보이는 영상만 보여 준다). */
    fun fillGaps(): Int {
        val videos = board.videos()
        val published = videos.filter { video -> video.published }
        // 갱신이 확인해서 볼 수 없다고 한 영상만. 만료로 확인 시각이 지워진 영상은 갱신이 다시 확인한다.
        val dead = published
            .filter { video -> !video.available && video.checkedAt != null }
            .sortedBy { video -> video.position }
        if (dead.isEmpty()) {
            return 0
        }
        val keptChannels = (published - dead.toSet()).mapNotNull { video -> video.channelTitle }.toSet()
        val picks = choose(dead.size, keptChannels, videos)
        if (picks.isEmpty()) {
            return 0
        }
        val removed = dead.take(picks.size)
        val placements = removed.zip(picks) { video, pick -> Placement(pick, video.position) }
        board.apply(BoardChange(removed, placements, false), clock.instant())
        return placements.size
    }

    /** 다시 확인한 기록 수. YouTube에서 사라진 영상의 기록은 지운다. */
    fun recheckUsed(): Int {
        val now = clock.instant()
        val ids = board.usedCheckedBefore(now.minus(USED_RECHECK_AGE), LOOKUP_BATCH_SIZE)
        if (ids.isEmpty()) {
            return 0
        }
        val found = lookup.lookup(ids).keys
        board.confirmUsed(found, now)
        board.discard(ids.filter { id -> id !in found })
        return ids.size
    }

    /**
     * 예비 후보를 순위대로 YouTube에서 다시 확인해 [count]개까지 고른다. 채널은 [takenChannels]와 서로 겹치지 않게 한다.
     * 지워졌거나 재생할 수 없게 된 후보는 지운다.
     */
    private fun choose(count: Int, takenChannels: Set<String>, videos: List<HomeVideo>): List<Metadata> {
        val onBoard = videos.map { video -> video.videoId }.toSet()
        val waiting = board.waiting().filter { candidate -> candidate.videoId !in onBoard }
        val channels = HashSet(takenChannels)
        val picks = ArrayList<Metadata>()
        val unusable = ArrayList<String>()
        for (batch in waiting.chunked(LOOKUP_BATCH_SIZE)) {
            val found = lookup.lookup(batch.map { candidate -> candidate.videoId })
            for (candidate in batch) {
                if (picks.size == count) {
                    break
                }
                val metadata = found[candidate.videoId]
                if (metadata == null || !metadata.available) {
                    unusable.add(candidate.videoId)
                    continue
                }
                val channel = metadata.channelTitle ?: continue
                if (channel in channels) {
                    continue
                }
                channels.add(channel)
                picks.add(metadata)
            }
            if (picks.size == count) {
                break
            }
        }
        board.discard(unusable)
        return picks
    }

    /** 새 영상은 뺀 영상의 자리를 순서대로 받고, 남는 새 영상은 표의 맨 뒤 자리를 받는다. */
    private fun place(picks: List<Metadata>, removed: List<HomeVideo>, videos: List<HomeVideo>): List<Placement> {
        val freed = removed.map { video -> video.position }.sorted()
        var next = (videos.maxOfOrNull { video -> video.position } ?: -1) + 1
        val placements = ArrayList<Placement>()
        for ((index, pick) in picks.withIndex()) {
            val position = if (index < freed.size) freed[index] else next++
            placements.add(Placement(pick, position))
        }
        return placements
    }

    private companion object {
        private const val SLOTS = 3
        private const val LOOKUP_BATCH_SIZE = 50
        private val WEEK: Duration = Duration.ofDays(7)
        private val DAY: Duration = Duration.ofDays(1)

        // 일주일마다 다시 확인하면 30일 기한 안에 네 번 기회가 있다.
        private val USED_RECHECK_AGE: Duration = Duration.ofDays(7)
    }
}
