package com.pawbridge.backend.community.internal.homevideo.application

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import java.time.Duration
import java.time.Instant

/** 홈에 올리기 직전의 재생 확인(YouTube videos.list). 없는 영상은 결과에서 빠진다. 실패하면 예외를 던진다. */
interface VideoLookup {
    fun lookup(ids: List<String>): Map<String, Metadata>
}

/** 자동 교체가 읽고 쓰는 홈 영상 표, 후보 표, 실행 기록. */
interface CurationBoard {
    /**
     * 마지막 성공에서 [interval]이 지났고 마지막 시도에서 [retryAfter]가 지났으면 시도 시각을 남기고 true.
     * 실행 기록 줄을 잠그고 확인하므로 서버 두 대가 동시에 물어도 한쪽만 true를 받는다.
     */
    fun claimWeekly(now: Instant, interval: Duration, retryAfter: Duration): Boolean

    /** 홈 영상 표의 모든 줄(게시 여부와 상관없이). */
    fun videos(): List<HomeVideo>

    /** 예비 후보. 검색 순위가 높은 것부터, 같은 순위면 최근에 확인한 것부터. */
    fun waiting(): List<WaitingCandidate>

    /** 쓸 수 없게 된 후보나 YouTube에서 사라진 사용 기록을 지운다. */
    fun discard(videoIds: Collection<String>)

    /**
     * [change]를 한 트랜잭션으로 반영한다: 홈 영상 잠금, 뺄 줄 삭제, 새 줄 게시, 사용 기록, 목록 버전 올리기.
     * 그사이 관리자가 표를 바꿔 계획이 맞지 않으면 아무것도 바꾸지 않고 [HomeVideoCurationException]을 던진다.
     */
    fun apply(change: BoardChange, now: Instant)

    /** 마지막 확인이 [cutoff] 이전인 사용 기록의 영상 ID. 오래된 것부터 [limit]개. */
    fun usedCheckedBefore(cutoff: Instant, limit: Int): List<String>

    /** 사용 기록을 YouTube에서 다시 확인했다고 남긴다. */
    fun confirmUsed(videoIds: Collection<String>, now: Instant)
}

@JvmRecord
data class WaitingCandidate(val videoId: String, val searchRank: Int)

@JvmRecord
data class BoardChange(
    val removed: List<HomeVideo>,
    val added: List<Placement>,
    /** 주간 교체면 true: 반영과 함께 마지막 성공 시각을 남긴다. */
    val weeklySucceeded: Boolean,
)

@JvmRecord
data class Placement(val metadata: Metadata, val position: Int)
