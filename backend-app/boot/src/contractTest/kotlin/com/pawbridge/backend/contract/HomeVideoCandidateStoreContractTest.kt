package com.pawbridge.backend.contract

import com.pawbridge.backend.community.internal.homevideo.application.JudgedCandidate
import com.pawbridge.backend.community.internal.homevideo.domain.CandidateStatus
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JpaHomeVideoCandidateStore
import java.sql.Timestamp
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException

/**
 * 홈 영상 후보 저장소(JPA)를 운영과 같은 조립으로 실제 PostgreSQL과 앱 역할 권한에서 확인한다.
 */
class HomeVideoCandidateStoreContractTest : JpaContractBase() {
    @Autowired
    private lateinit var store: JpaHomeVideoCandidateStore

    private val now: Instant = Instant.parse("2026-11-11T00:00:00Z")

    @Test
    fun `판정 결과를 저장했을 때__표를 보면__상태와 순위와 확인 시각이 그대로 남는다`() {
        // given
        val candidates = listOf(
            JudgedCandidate("Candidate01", "UCchannel01", CandidateStatus.WAITING, 0),
            JudgedCandidate("Candidate02", "UCchannel02", CandidateStatus.REJECTED, 3),
        )

        // when
        store.saveJudged(candidates, now)

        // then
        val rows = ContractPostgres.admin.queryForList(
            "SELECT video_id, channel_id, status, search_rank, checked_at " +
                "FROM pawbridge_community.home_video_candidates ORDER BY video_id",
        )
        assertThat(rows).hasSize(2)
        assertThat(rows[0]["video_id"]).isEqualTo("Candidate01")
        assertThat(rows[0]["channel_id"]).isEqualTo("UCchannel01")
        assertThat(rows[0]["status"]).isEqualTo("WAITING")
        assertThat(rows[0]["search_rank"]).isEqualTo(0)
        assertThat((rows[0]["checked_at"] as Timestamp).toInstant()).isEqualTo(now)
        assertThat(rows[1]["status"]).isEqualTo("REJECTED")
        assertThat(rows[1]["search_rank"]).isEqualTo(3)
    }

    @Test
    fun `기록된 영상과 처음 보는 영상이 섞였을 때__이미 본 것을 물으면__기록된 것만 돌려준다`() {
        // given
        seedCandidate("Candidate01", "USED", now)
        seedCandidate("Candidate02", "REJECTED", now)

        // when
        val known = store.knownIds(listOf("Candidate01", "Candidate02", "FreshVideo1"))

        // then
        assertThat(known).containsExactlyInAnyOrder("Candidate01", "Candidate02")
    }

    @Test
    fun `확인한 지 오래된 줄과 최근 줄이 있을 때__기준 시각으로 지우면__기준 이전과 같은 시각만 지운다`() {
        // given
        val cutoff = now.minusSeconds(29L * 24 * 3600)
        seedCandidate("OldVideo001", "WAITING", cutoff.minusSeconds(1))
        seedCandidate("EdgeVideo01", "USED", cutoff)
        seedCandidate("NewVideo001", "REJECTED", cutoff.plusSeconds(1))

        // when
        val deleted = store.deleteCheckedBefore(cutoff)

        // then
        assertThat(deleted).isEqualTo(2)
        val left = ContractPostgres.admin.queryForList(
            "SELECT video_id FROM pawbridge_community.home_video_candidates",
            String::class.java,
        )
        assertThat(left).containsExactly("NewVideo001")
    }

    @Test
    fun `이미 사용한 영상을 겹친 작업이 예비로 저장하려 할 때__저장하면__실패하고 사용 기록은 그대로 남는다`() {
        // given
        seedCandidate("Candidate01", "USED", now)
        val late = listOf(
            JudgedCandidate("Candidate02", "UCchannel02", CandidateStatus.WAITING, 0),
            JudgedCandidate("Candidate01", "UCchannel01", CandidateStatus.WAITING, 1),
        )

        // when
        val thrown = assertThatThrownBy { store.saveJudged(late, now) }

        // then
        thrown.isInstanceOf(DataAccessException::class.java)
        val rows = ContractPostgres.admin.queryForList(
            "SELECT video_id || ':' || status FROM pawbridge_community.home_video_candidates",
            String::class.java,
        )
        assertThat(rows).containsExactly("Candidate01:USED")
    }

    @Test
    fun `앱 역할에 삭제 권한이 없을 때__지우면__실패한다`() {
        // given
        seedCandidate("OldVideo001", "WAITING", now.minusSeconds(60))

        // when, then
        ContractPostgres.withRevoked("DELETE", "pawbridge_community.home_video_candidates") {
            assertThatThrownBy { store.deleteCheckedBefore(now) }.hasMessageContaining("permission denied")
        }
    }

    private fun seedCandidate(videoId: String, status: String, checkedAt: Instant) {
        execute(
            "INSERT INTO pawbridge_community.home_video_candidates(video_id, channel_id, status, search_rank, checked_at) " +
                "VALUES (?, 'UCseed', ?, 0, ?)",
            videoId,
            status,
            Timestamp.from(checkedAt),
        )
    }
}
