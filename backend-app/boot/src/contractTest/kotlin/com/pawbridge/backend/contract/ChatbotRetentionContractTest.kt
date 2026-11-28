package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.JdbcChatbotRetention
import com.pawbridge.backend.boot.configuration.ChatbotRetentionConfiguration.ChatbotRetentionJob
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * AI 입양 도우미 기록의 1년 보관 정리를 실제 PostgreSQL과 앱 역할 권한으로 확인한다.
 *
 * 정리 작업은 `pawbridge.jobs.cleanup-enabled=true`에서만 조립되어 공유 컨텍스트에는 빈이 없다. 구성과 같은 방식으로
 * 앱의 쓰기 풀과 트랜잭션 템플릿으로 만들어 실행한다. 시계는 2027-12-01 00:00(서버 기준)으로 고정해 기준 시각이
 * 2026-12-01 00:00이 된다.
 */
class ChatbotRetentionContractTest : ContractTestBase() {
    @Autowired
    @Qualifier("writeJdbc")
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    @Qualifier("writeTransaction")
    private lateinit var transaction: TransactionTemplate

    private val admin get() = ContractPostgres.admin

    @BeforeEach
    fun seedAnimal() {
        admin.update(
            "INSERT INTO pawbridge_animal.shelters(id, created_at, care_reg_no, name, address, phone) " +
                "VALUES (?, '2026-01-01 00:00:00', 'R-care-1', '보관 시험 보호소', '합성 주소', '000-0000-0000')",
            ANIMAL,
        )
        admin.update(
            "INSERT INTO pawbridge_animal.animals(id, created_at, api_source, apms_notice_no, favorite_count, gender, " +
                "neuter_status, notice_end_date, notice_start_date, species, status, shelter_id) " +
                "VALUES (?, '2026-01-01 00:00:00', 'MANUAL', 'R-1', 0, 'MALE', 'UNKNOWN', '2026-03-20', '2026-02-01', 'DOG', 'PROTECT', ?)",
            ANIMAL, ANIMAL,
        )
    }

    @AfterEach
    fun removeRows() {
        admin.update(
            "DELETE FROM pawbridge_animal.chatbot_messages WHERE session_id IN " +
                "(SELECT id FROM pawbridge_animal.chatbot_sessions WHERE animal_id = ?)",
            ANIMAL,
        )
        admin.update("DELETE FROM pawbridge_animal.chatbot_sessions WHERE animal_id = ?", ANIMAL)
        admin.update("DELETE FROM pawbridge_animal.chatbot_block_logs WHERE animal_id = ?", ANIMAL)
        admin.update("DELETE FROM pawbridge_animal.animals WHERE id = ?", ANIMAL)
        admin.update("DELETE FROM pawbridge_animal.shelters WHERE id = ?", ANIMAL)
    }

    @Test
    fun `1년이 지난 기록과 최근 기록이 섞여 있을 때__정리하면__지난 메시지와 빈 대화와 제한 기록만 지운다`() {
        // given
        seedSession("r-old-only", OLD)
        seedMessage("r-old-only", OLD)
        seedMessage("r-old-only", OLD)
        // 1년 전에 시작했지만 최근에도 질문한 대화: 지난 메시지만 지우고 대화는 남긴다.
        seedSession("r-continued", OLD)
        seedMessage("r-continued", OLD)
        seedMessage("r-continued", RECENT)
        seedSession("r-recent", RECENT)
        seedMessage("r-recent", RECENT)
        // 질문이 막혀 메시지 없이 남은 최근 대화는 기간 전이라 남긴다.
        seedSession("r-recent-empty", RECENT)
        seedBlockLog(OLD)
        seedBlockLog(RECENT)

        // when
        job().deleteExpired()

        // then
        assertThat(sessions()).containsExactlyInAnyOrder("r-continued", "r-recent", "r-recent-empty")
        assertThat(messageTimes("r-continued")).containsExactly(RECENT)
        assertThat(messageTimes("r-recent")).containsExactly(RECENT)
        assertThat(blockLogTimes()).containsExactly(RECENT)
    }

    @Test
    fun `기준 시각과 같은 시각의 기록일 때__정리하면__지우지 않는다`() {
        // given
        seedSession("r-boundary", CUTOFF)
        seedMessage("r-boundary", CUTOFF)
        seedBlockLog(CUTOFF)

        // when
        job().deleteExpired()

        // then
        assertThat(sessions()).containsExactly("r-boundary")
        assertThat(messageTimes("r-boundary")).containsExactly(CUTOFF)
        assertThat(blockLogTimes()).containsExactly(CUTOFF)
    }

    @Test
    fun `지난 메시지가 한 묶음보다 많을 때__정리하면__여러 묶음으로 나눠 모두 지운다`() {
        // given
        seedSession("r-many", OLD)
        admin.update(
            "INSERT INTO pawbridge_animal.chatbot_messages(session_id, role, content, created_at) " +
                "SELECT 'r-many', 'USER', '합성 질문', ?::timestamp FROM generate_series(1, 2500)",
            OLD,
        )

        // when
        job().deleteExpired()

        // then
        assertThat(messageTimes("r-many")).isEmpty()
        assertThat(sessions()).isEmpty()
    }

    private fun job(): ChatbotRetentionJob {
        val clock = Clock.fixed(LocalDateTime.parse(NOW).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())
        return ChatbotRetentionJob(JdbcChatbotRetention(jdbc), transaction, clock)
    }

    private fun seedSession(id: String, createdAt: String) {
        admin.update(
            "INSERT INTO pawbridge_animal.chatbot_sessions(id, anonymous_session_id, animal_id, created_at) VALUES (?, ?, ?, ?::timestamp)",
            id, "anon-$id", ANIMAL, createdAt,
        )
    }

    private fun seedMessage(session: String, createdAt: String) {
        admin.update(
            "INSERT INTO pawbridge_animal.chatbot_messages(session_id, role, content, created_at) VALUES (?, 'USER', '합성 질문', ?::timestamp)",
            session, createdAt,
        )
    }

    private fun seedBlockLog(createdAt: String) {
        admin.update(
            "INSERT INTO pawbridge_animal.chatbot_block_logs(animal_id, anonymous_session_id, ip_hash, category, reason, " +
                "question_length, question_preview, created_at) VALUES (?, 'anon-block', ?, 'RATE_LIMIT', 'SESSION_MESSAGE_LIMIT', 4, '합성 질문', ?::timestamp)",
            ANIMAL, "0".repeat(64), createdAt,
        )
    }

    private fun sessions(): List<String> = admin.queryForList(
        "SELECT id FROM pawbridge_animal.chatbot_sessions WHERE animal_id = ?",
        String::class.java,
        ANIMAL,
    )

    private fun messageTimes(session: String): List<String> = admin.queryForList(
        "SELECT to_char(created_at, 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM pawbridge_animal.chatbot_messages WHERE session_id = ? ORDER BY id",
        String::class.java,
        session,
    )

    private fun blockLogTimes(): List<String> = admin.queryForList(
        "SELECT to_char(created_at, 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM pawbridge_animal.chatbot_block_logs WHERE animal_id = ? ORDER BY id",
        String::class.java,
        ANIMAL,
    )

    private companion object {
        const val ANIMAL = 9_988_501L
        const val NOW = "2027-12-01T00:00:00"
        const val CUTOFF = "2026-12-01T00:00:00"
        const val OLD = "2026-11-30T23:59:59"
        const val RECENT = "2026-12-01T00:00:01"
    }
}
