package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.application.BoardChange
import com.pawbridge.backend.community.internal.homevideo.application.CurationBoard
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoCurationException
import com.pawbridge.backend.community.internal.homevideo.application.WaitingCandidate
import com.pawbridge.backend.community.internal.homevideo.domain.CandidateStatus
import java.time.Duration
import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.support.TransactionOperations

/** 각 메서드가 짧은 트랜잭션 하나다. YouTube, Gemini 호출은 이 트랜잭션들 밖에서 일어난다. */
class JpaCurationBoard(
    private val videos: HomeVideoEntityRepository,
    private val boards: HomeVideoBoardRepository,
    private val candidates: HomeVideoCandidateRepository,
    private val states: HomeVideoCurationRepository,
    private val transaction: TransactionOperations,
) : CurationBoard {

    override fun claimWeekly(now: Instant, interval: Duration, retryAfter: Duration): Boolean =
        transaction.execute {
            val state = states.lockState()
                ?: throw HomeVideoCurationException("홈 영상 자동 교체 실행 기록(V14)이 없습니다.")
            val succeeded = state.lastSucceededAt
            val attempted = state.lastAttemptedAt
            val dueWeekly = succeeded == null || !succeeded.isAfter(now.minus(interval))
            val dueRetry = attempted == null || !attempted.isAfter(now.minus(retryAfter))
            if (dueWeekly && dueRetry) {
                state.attempted(now)
            }
            dueWeekly && dueRetry
        }!!

    override fun videos(): List<HomeVideo> =
        transaction.execute {
            videos.findAll().map { video -> video.toHomeVideo() }
        }!!

    override fun waiting(): List<WaitingCandidate> =
        transaction.execute {
            candidates.findByStatusOrderBySearchRankAscCheckedAtDesc(CandidateStatus.WAITING)
                .map { candidate -> WaitingCandidate(candidate.videoId!!, candidate.searchRank!!) }
        }!!

    override fun discard(videoIds: Collection<String>) {
        if (videoIds.isEmpty()) {
            return
        }
        transaction.executeWithoutResult {
            candidates.deleteAllById(videoIds)
        }
    }

    override fun apply(change: BoardChange, now: Instant) {
        transaction.executeWithoutResult {
            val board = boards.lockBoard() ?: throw HomeVideoCurationException("홈 영상 목록 줄이 없습니다.")
            val current = videos.findAll()
            checkPlan(change, current)

            val removedRows = current.filter { row -> change.removed.any { video -> video.id == row.rowId } }
            videos.deleteAll(removedRows)
            // Hibernate는 INSERT를 DELETE보다 먼저 내보낸다. 고유 조건이 순서에 걸리지 않게 지우기를 먼저 보낸다.
            videos.flush()
            for (placement in change.added) {
                videos.save(HomeVideoEntity.newlyPublished(placement.metadata, placement.position, now))
            }

            val usedIds = removedRows.map { row -> row.videoId!! } + change.added.map { placement -> placement.metadata.videoId }
            markUsed(usedIds, now)
            board.changed()
            if (change.weeklySucceeded) {
                states.lockState()!!.succeeded(now)
            }
        }
    }

    override fun usedCheckedBefore(cutoff: Instant, limit: Int): List<String> =
        transaction.execute {
            candidates.findByStatusAndCheckedAtLessThanEqualOrderByCheckedAt(
                CandidateStatus.USED,
                cutoff,
                PageRequest.of(0, limit),
            ).map { candidate -> candidate.videoId!! }
        }!!

    override fun confirmUsed(videoIds: Collection<String>, now: Instant) {
        if (videoIds.isEmpty()) {
            return
        }
        transaction.executeWithoutResult {
            for (candidate in candidates.findAllById(videoIds)) {
                candidate.confirm(now)
            }
        }
    }

    /** 계획을 세운 뒤 관리자가 표를 바꿨으면 이번에는 바꾸지 않는다(다음 시도에서 새로 계획한다). */
    private fun checkPlan(change: BoardChange, current: List<HomeVideoEntity>) {
        for (video in change.removed) {
            val row = current.firstOrNull { candidate -> candidate.rowId == video.id }
            if (row == null || row.videoId != video.videoId || !row.published) {
                throw HomeVideoCurationException("홈 영상이 그사이 바뀌어 이번 교체를 건너뜁니다.")
            }
        }
        val remaining = current.filter { row -> change.removed.none { video -> video.id == row.rowId } }
        val addedIds = change.added.map { placement -> placement.metadata.videoId }
        if (remaining.any { row -> row.videoId in addedIds }) {
            throw HomeVideoCurationException("새 영상이 그사이 홈 영상 표에 들어와 이번 교체를 건너뜁니다.")
        }
        if (remaining.count { row -> row.published } + change.added.size > SLOTS) {
            throw HomeVideoCurationException("게시 영상이 그사이 늘어 이번 교체를 건너뜁니다.")
        }
    }

    private fun markUsed(videoIds: List<String>, now: Instant) {
        val existing = candidates.findAllById(videoIds).associateBy { candidate -> candidate.videoId!! }
        for (videoId in videoIds) {
            val candidate = existing[videoId]
            if (candidate != null) {
                candidate.markUsed(now)
            } else {
                // 관리자가 손으로 올린 영상: 채널 ID와 검색 순위는 모른다.
                candidates.save(HomeVideoCandidate(videoId, null, CandidateStatus.USED, 0, now))
            }
        }
    }

    private companion object {
        private const val SLOTS = 3
    }
}
