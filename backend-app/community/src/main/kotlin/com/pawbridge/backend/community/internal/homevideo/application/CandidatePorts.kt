package com.pawbridge.backend.community.internal.homevideo.application

import com.pawbridge.backend.community.internal.homevideo.domain.CandidateStatus
import com.pawbridge.backend.community.internal.homevideo.domain.CandidateVideo
import java.time.Instant

/** 자동 검색이 쓰는 YouTube. 호출이 실패하면 예외를 던진다. */
interface CandidateSource {
    /** 검색 결과 순서 그대로의 영상 ID. 한 쪽(최대 50개)마다 할당량 100을 쓴다. */
    fun search(query: String, publishedAfter: Instant, pages: Int): List<String>

    /** 영상 상세. 지워졌거나 비공개인 영상은 결과에서 빠진다. */
    fun details(ids: List<String>): List<CandidateVideo>
}

/** 영상이 홈 목적에 맞는지 판정한다. */
interface CandidateJudge {
    /** 영상 ID마다 맞으면 true. 답이 빠진 영상은 결과에 없다. 판정을 받지 못하면 예외를 던진다. */
    fun judge(videos: List<CandidateVideo>): Map<String, Boolean>
}

/** 판정 결과와 사용 기록. */
interface CandidateStore {
    /** 마지막 확인이 [cutoff] 이전인 줄을 지우고 지운 수를 돌려준다. */
    fun deleteCheckedBefore(cutoff: Instant): Int

    /** [ids] 가운데 이미 기록된 영상 ID. */
    fun knownIds(ids: Collection<String>): Set<String>

    fun saveJudged(candidates: List<JudgedCandidate>, checkedAt: Instant)
}

/** 지금 홈 영상 표에 있는 영상(게시 여부와 상관없이). */
interface CurrentHomeVideos {
    fun videoIds(): Set<String>
}

@JvmRecord
data class JudgedCandidate(
    val videoId: String,
    val channelId: String,
    val status: CandidateStatus,
    val searchRank: Int,
)

/** 자동 선정을 이번에는 하지 않고 지금 영상을 그대로 두어야 할 때. 메시지에 비밀값을 넣지 않는다. */
class HomeVideoCurationException(message: String) : RuntimeException(message)
