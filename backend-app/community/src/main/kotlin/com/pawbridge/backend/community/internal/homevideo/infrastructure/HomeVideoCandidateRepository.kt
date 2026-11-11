package com.pawbridge.backend.community.internal.homevideo.infrastructure

import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface HomeVideoCandidateRepository : JpaRepository<HomeVideoCandidate, String> {

    // 엔티티를 하나씩 읽어 지우지 않고 한 문장으로 지운다.
    @Modifying
    @Query("DELETE FROM HomeVideoCandidate candidate WHERE candidate.checkedAt <= :cutoff")
    fun deleteCheckedAtOrBefore(@Param("cutoff") cutoff: Instant): Int
}
