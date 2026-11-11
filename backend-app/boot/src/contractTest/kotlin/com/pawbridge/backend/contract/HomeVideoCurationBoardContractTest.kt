package com.pawbridge.backend.contract

import com.pawbridge.backend.community.internal.homevideo.application.BoardChange
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoCurationException
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import com.pawbridge.backend.community.internal.homevideo.application.Placement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JpaCurationBoard
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 자동 교체 저장소(JPA)를 운영과 같은 조립으로 실제 PostgreSQL과 앱 역할 권한에서 확인한다:
 * 실행 기록 잠금, 한 트랜잭션 반영(삭제, 게시, 사용 기록, 목록 버전), 계획이 낡았을 때 아무것도 바꾸지 않기.
 */
class HomeVideoCurationBoardContractTest : JpaContractBase() {
    @Autowired
    private lateinit var board: JpaCurationBoard

    private val now: Instant = Instant.parse("2026-11-18T00:00:00Z")
    private val week: Duration = Duration.ofDays(7)
    private val day: Duration = Duration.ofDays(1)

    @Test
    fun `한 번도 돌지 않았을 때__주간 교체를 잡으면__시도 시각을 남기고 바로 다시 잡으면 거절한다`() {
        // when
        val first = board.claimWeekly(now, week, day)
        val second = board.claimWeekly(now.plusSeconds(60), week, day)

        // then
        assertThat(first).isTrue()
        assertThat(second).isFalse()
        assertThat(state("last_attempted_at")).isEqualTo(now)
        assertThat(state("last_succeeded_at")).isNull()
    }

    @Test
    fun `일주일 전에 성공했을 때__주간 교체를 잡으면__잡는다`() {
        // given
        setState(succeeded = now.minus(week), attempted = now.minus(week))

        // when, then
        assertThat(board.claimWeekly(now, week, day)).isTrue()
    }

    @Test
    fun `성공한 지 일주일이 안 됐을 때__주간 교체를 잡으면__거절한다`() {
        // given
        setState(succeeded = now.minus(week).plusSeconds(60), attempted = now.minus(week))

        // when, then
        assertThat(board.claimWeekly(now, week, day)).isFalse()
    }

    @Test
    fun `지난 시도가 하루 안에 실패했을 때__주간 교체를 잡으면__하루가 지날 때까지 거절한다`() {
        // given
        setState(succeeded = now.minus(Duration.ofDays(8)), attempted = now.minus(Duration.ofHours(2)))

        // when, then
        assertThat(board.claimWeekly(now, week, day)).isFalse()
        assertThat(board.claimWeekly(now.plus(Duration.ofHours(22)), week, day)).isTrue()
    }

    @Test
    fun `교체 계획이 맞을 때__반영하면__뺀 줄을 지우고 새 영상을 게시하고 사용 기록과 목록 버전과 성공 시각을 남긴다`() {
        // given
        val oldId = seedVideo("OldVideo001", position = 1)
        seedVideo("KeptVideo01", position = 0)
        seedCandidate("NewVideo001", "WAITING")
        execute("UPDATE pawbridge_community.home_video_board SET revision = 7")
        val removed = board.videos().filter { video -> video.id == oldId }
        val change = BoardChange(removed, listOf(Placement(metadata("NewVideo001"), 1)), true)

        // when
        board.apply(change, now)

        // then
        val rows = ContractPostgres.admin.queryForList(
            "SELECT video_id, published, position, title, channel_title, thumbnail_url, duration_seconds, available, checked_at " +
                "FROM pawbridge_community.home_videos ORDER BY position",
        )
        assertThat(rows.map { row -> row["video_id"] }).containsExactly("KeptVideo01", "NewVideo001")
        val added = rows[1]
        assertThat(added["published"]).isEqualTo(true)
        assertThat(added["title"]).isEqualTo("제목 NewVideo001")
        assertThat(added["channel_title"]).isEqualTo("채널 NewVideo001")
        assertThat(added["duration_seconds"]).isEqualTo(600L)
        assertThat(added["available"]).isEqualTo(true)
        assertThat((added["checked_at"] as Timestamp).toInstant()).isEqualTo(now)
        assertThat(candidates()).containsExactly("NewVideo001:USED", "OldVideo001:USED")
        assertThat(ContractPostgres.admin.queryForObject(
            "SELECT revision FROM pawbridge_community.home_video_board",
            Long::class.java,
        )).isEqualTo(8L)
        assertThat(state("last_succeeded_at")).isEqualTo(now)
    }

    @Test
    fun `빈칸 채우기일 때__반영하면__성공 시각은 남기지 않는다`() {
        // given
        seedCandidate("NewVideo001", "WAITING")

        // when
        board.apply(BoardChange(emptyList(), listOf(Placement(metadata("NewVideo001"), 0)), false), now)

        // then
        assertThat(state("last_succeeded_at")).isNull()
    }

    @Test
    fun `계획을 세운 뒤 관리자가 뺄 영상을 숨겼을 때__반영하면__아무것도 바꾸지 않고 실패한다`() {
        // given
        val oldId = seedVideo("OldVideo001", position = 0)
        seedCandidate("NewVideo001", "WAITING")
        val removed = board.videos().filter { video -> video.id == oldId }
        execute("UPDATE pawbridge_community.home_videos SET published = false WHERE id = ?", oldId)
        val before = ContractPostgres.snapshot()

        // when
        val thrown = assertThatThrownBy {
            board.apply(BoardChange(removed, listOf(Placement(metadata("NewVideo001"), 0)), true), now)
        }

        // then
        thrown.isInstanceOf(HomeVideoCurationException::class.java)
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `게시 영상이 이미 셋일 때__빼는 것 없이 하나를 더하려 하면__아무것도 바꾸지 않고 실패한다`() {
        // given
        seedVideo("OldVideo001", position = 0)
        seedVideo("OldVideo002", position = 1)
        seedVideo("OldVideo003", position = 2)
        val before = ContractPostgres.snapshot()

        // when, then
        assertThatThrownBy {
            board.apply(BoardChange(emptyList(), listOf(Placement(metadata("NewVideo001"), 3)), false), now)
        }.isInstanceOf(HomeVideoCurationException::class.java)
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `예비 후보가 여럿일 때__읽으면__검색 순위가 높은 것부터 같은 순위면 최근 확인한 것부터 준다`() {
        // given
        seedCandidate("Rank1Old001", "WAITING", rank = 1, checkedAt = now.minusSeconds(600))
        seedCandidate("Rank1New001", "WAITING", rank = 1, checkedAt = now)
        seedCandidate("Rank0Video1", "WAITING", rank = 0)
        seedCandidate("Rejected001", "REJECTED", rank = 0)

        // when
        val waiting = board.waiting()

        // then
        assertThat(waiting.map { candidate -> candidate.videoId }).containsExactly("Rank0Video1", "Rank1New001", "Rank1Old001")
    }

    @Test
    fun `사용 기록이 여럿일 때__오래된 것을 물으면__기준 이전 사용 기록만 오래된 순서로 개수만큼 주고 확인하면 시각이 바뀐다`() {
        // given
        val cutoff = now.minus(week)
        seedCandidate("UsedOld0001", "USED", checkedAt = cutoff.minusSeconds(120))
        seedCandidate("UsedOld0002", "USED", checkedAt = cutoff.minusSeconds(60))
        seedCandidate("UsedNew0001", "USED", checkedAt = cutoff.plusSeconds(60))
        seedCandidate("WaitOld0001", "WAITING", checkedAt = cutoff.minusSeconds(600))

        // when
        val stale = board.usedCheckedBefore(cutoff, 1)
        board.confirmUsed(listOf("UsedOld0001"), now)
        board.discard(listOf("UsedOld0002"))

        // then
        assertThat(stale).containsExactly("UsedOld0001")
        assertThat(board.usedCheckedBefore(cutoff, 10)).isEmpty()
        assertThat(candidates()).containsExactly("UsedNew0001:USED", "UsedOld0001:USED", "WaitOld0001:WAITING")
    }

    private fun seedVideo(videoId: String, position: Int): UUID {
        val id = UUID.randomUUID()
        execute(
            "INSERT INTO pawbridge_community.home_videos(id, video_id, published, position, created_at, title, channel_title, " +
                "thumbnail_url, duration_seconds, available, checked_at) VALUES (?, ?, true, ?, ?, ?, ?, ?, 600, true, ?)",
            id,
            videoId,
            position,
            Timestamp.from(now.minus(Duration.ofDays(10))),
            "제목 $videoId",
            "채널 $videoId",
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            Timestamp.from(now.minusSeconds(60)),
        )
        return id
    }

    private fun seedCandidate(videoId: String, status: String, rank: Int = 0, checkedAt: Instant = now) {
        execute(
            "INSERT INTO pawbridge_community.home_video_candidates(video_id, channel_id, status, search_rank, checked_at) " +
                "VALUES (?, 'UCseed', ?, ?, ?)",
            videoId,
            status,
            rank,
            Timestamp.from(checkedAt),
        )
    }

    private fun setState(succeeded: Instant, attempted: Instant) {
        execute(
            "UPDATE pawbridge_community.home_video_curation SET last_succeeded_at = ?, last_attempted_at = ?",
            Timestamp.from(succeeded),
            Timestamp.from(attempted),
        )
    }

    private fun state(column: String): Instant? =
        ContractPostgres.admin.queryForObject(
            "SELECT $column FROM pawbridge_community.home_video_curation WHERE id = 1",
            Timestamp::class.java,
        )?.toInstant()

    private fun candidates(): List<String> =
        ContractPostgres.admin.queryForList(
            "SELECT video_id || ':' || status FROM pawbridge_community.home_video_candidates ORDER BY video_id",
            String::class.java,
        )

    private fun metadata(videoId: String): Metadata =
        Metadata(videoId, "제목 $videoId", "채널 $videoId", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg", 600, true)
}
