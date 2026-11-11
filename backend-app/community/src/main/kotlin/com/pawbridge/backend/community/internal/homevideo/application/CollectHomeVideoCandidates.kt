package com.pawbridge.backend.community.internal.homevideo.application

import com.pawbridge.backend.community.internal.homevideo.domain.CandidateRules
import com.pawbridge.backend.community.internal.homevideo.domain.CandidateStatus
import com.pawbridge.backend.community.internal.homevideo.domain.CandidateVideo
import com.pawbridge.backend.community.internal.homevideo.domain.CurationCriteria
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 홈 영상 후보 모으기: YouTube 검색 → 규칙으로 거르기 → 판정 → 결과 저장.
 *
 * 판정 묶음마다 정답 예시 영상을 섞어 넣고, 예시가 하나라도 탈락하면 아무것도 저장하지 않는다.
 * 외부 호출이 실패해도 저장하지 않는다. 그래서 실패한 주에는 지금 홈 영상이 그대로 남는다.
 */
class CollectHomeVideoCandidates(
    private val source: CandidateSource,
    private val judge: CandidateJudge,
    private val store: CandidateStore,
    private val homeVideos: CurrentHomeVideos,
    private val clock: Clock,
) {

    fun collect(): Summary {
        val now = clock.instant()
        // 약관: API로 받은 자료는 30일 안에 갱신하거나 지운다. 하루 여유를 두고 29일 지난 것을 지운다.
        store.deleteCheckedBefore(now.minus(RETENTION))

        val found = searchInRankOrder(now)
        // 정답 예시는 판정마다 따로 섞어 넣으므로 후보에서 뺀다.
        val known = store.knownIds(found) + homeVideos.videoIds() + CurationCriteria.REFERENCE_VIDEO_IDS
        val fresh = found.filter { id -> id !in known }
        if (fresh.isEmpty()) {
            return Summary(found.size, 0, 0, 0, 0)
        }
        val detailsById = source.details(fresh).associateBy { video -> video.videoId }
        val passed = fresh
            .mapNotNull { id -> detailsById[id] }
            .filter { video -> CandidateRules.passes(video, now) }
        if (passed.isEmpty()) {
            return Summary(found.size, fresh.size, 0, 0, 0)
        }

        val references = source.details(CurationCriteria.REFERENCE_VIDEO_IDS)
        if (references.isEmpty()) {
            throw HomeVideoCurationException("정답 예시 영상을 YouTube에서 찾지 못해 판정을 검증할 수 없습니다.")
        }
        val rankById = found.withIndex().associate { (index, id) -> id to index }
        val judged = ArrayList<JudgedCandidate>()
        for (batch in passed.chunked(JUDGE_BATCH_SIZE)) {
            judged.addAll(judgeBatch(batch, references, rankById))
        }
        store.saveJudged(judged, now)
        val waiting = judged.count { candidate -> candidate.status == CandidateStatus.WAITING }
        return Summary(found.size, fresh.size, passed.size, waiting, judged.size - waiting)
    }

    /** 검색어마다 순서를 지키며 한 줄로 합친다: 각 검색어 1위들, 2위들 순서. 먼저 나온 자리만 남긴다. */
    private fun searchInRankOrder(now: Instant): List<String> {
        val publishedAfter = now.minus(MAX_AGE)
        val results = QUERIES.map { query -> source.search(query, publishedAfter, PAGES_PER_QUERY) }
        val merged = LinkedHashSet<String>()
        val longest = results.maxOf { ids -> ids.size }
        for (index in 0 until longest) {
            for (ids in results) {
                if (index < ids.size) {
                    merged.add(ids[index])
                }
            }
        }
        return merged.toList()
    }

    private fun judgeBatch(
        batch: List<CandidateVideo>,
        references: List<CandidateVideo>,
        rankById: Map<String, Int>,
    ): List<JudgedCandidate> {
        val verdicts = judge.judge(batch + references)
        val distrusted = references.any { reference -> verdicts[reference.videoId] != true }
        if (distrusted) {
            throw HomeVideoCurationException("정답 예시 영상이 판정을 통과하지 못해 이번 판정을 버립니다.")
        }
        val judged = ArrayList<JudgedCandidate>()
        for (video in batch) {
            // 답이 빠진 영상은 저장하지 않아 다음 검색에서 다시 판정한다.
            val fit = verdicts[video.videoId] ?: continue
            val status = if (fit) CandidateStatus.WAITING else CandidateStatus.REJECTED
            judged.add(JudgedCandidate(video.videoId, video.channelId, status, rankById.getValue(video.videoId)))
        }
        return judged
    }

    @JvmRecord
    data class Summary(
        val found: Int,
        val fresh: Int,
        val passed: Int,
        val waiting: Int,
        val rejected: Int,
    )

    companion object {
        /** 맛보기에서 후보가 넉넉했던 검색어(2026-11-11). 검색어 4개 × 2쪽이면 할당량 약 800. */
        val QUERIES = listOf("유기견 입양 브이로그", "보호소 입양 브이로그", "유기묘 입양", "포인핸드 입양")
        const val PAGES_PER_QUERY = 2

        // 한 번에 판정할 수. 맛보기 67개 판정에 출력 약 3,600토큰을 썼다. 묶음을 줄여 답이 잘리지 않게 한다.
        const val JUDGE_BATCH_SIZE = 40

        private val MAX_AGE: Duration = Duration.ofDays(365L * 3)
        private val RETENTION: Duration = Duration.ofDays(29)
    }
}
