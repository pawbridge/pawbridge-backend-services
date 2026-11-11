package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.application.CandidateStore
import com.pawbridge.backend.community.internal.homevideo.application.JudgedCandidate
import java.time.Instant
import org.springframework.transaction.support.TransactionOperations

/** 각 메서드가 짧은 트랜잭션 하나다. 외부 호출(YouTube, Gemini)은 이 트랜잭션들 밖에서 일어난다. */
class JpaHomeVideoCandidateStore(
    private val repository: HomeVideoCandidateRepository,
    private val transaction: TransactionOperations,
) : CandidateStore {

    override fun deleteCheckedBefore(cutoff: Instant): Int =
        transaction.execute { repository.deleteCheckedAtOrBefore(cutoff) }!!

    override fun knownIds(ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) {
            return emptySet()
        }
        return transaction.execute {
            repository.findAllById(ids)
                .map { candidate -> candidate.videoId!! }
                .toSet()
        }!!
    }

    override fun saveJudged(candidates: List<JudgedCandidate>, checkedAt: Instant) {
        if (candidates.isEmpty()) {
            return
        }
        val rows = candidates.map { candidate ->
            HomeVideoCandidate(candidate.videoId, candidate.channelId, candidate.status, candidate.searchRank, checkedAt)
        }
        transaction.executeWithoutResult {
            repository.saveAll(rows)
        }
    }
}
