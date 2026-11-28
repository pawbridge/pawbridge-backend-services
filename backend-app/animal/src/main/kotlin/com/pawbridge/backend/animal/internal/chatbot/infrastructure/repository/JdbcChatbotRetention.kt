package com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository

import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.LocalDateTime

/**
 * 보관 기간이 지난 AI 입양 도우미 기록을 지운다. 한 번에 [limit]행까지만 지워 각 삭제를 짧게 유지하고,
 * 남은 행은 다음 호출이 이어서 지운다. 시각은 엔티티 감사 필드와 같은 서버 기준 [LocalDateTime]이다.
 *
 * 메시지를 먼저 지우고, 메시지가 하나도 남지 않은 오래된 세션을 그다음에 지운다(메시지가 세션을 참조한다).
 * 최근 메시지가 남은 세션은 시작일이 오래돼도 남는다.
 */
class JdbcChatbotRetention(private val jdbc: JdbcOperations) {

    /** [cutoff]보다 먼저 저장된 질문과 답변을 오래된 순으로 지운다. */
    fun deleteMessagesBefore(cutoff: LocalDateTime, limit: Int): Int {
        return jdbc.update(DELETE_MESSAGES_SQL, Timestamp.valueOf(cutoff), limit)
    }

    /** [cutoff]보다 먼저 시작했고 남은 메시지가 없는 대화를 지운다. */
    fun deleteEmptySessionsBefore(cutoff: LocalDateTime, limit: Int): Int {
        return jdbc.update(DELETE_SESSIONS_SQL, Timestamp.valueOf(cutoff), limit)
    }

    /** [cutoff]보다 먼저 남긴 제한 기록(사유, 질문 앞부분, 변환한 IP)을 지운다. */
    fun deleteBlockLogsBefore(cutoff: LocalDateTime, limit: Int): Int {
        return jdbc.update(DELETE_BLOCK_LOGS_SQL, Timestamp.valueOf(cutoff), limit)
    }

    private companion object {
        // id가 저장 순서대로 커지므로 기본 키 순서로 오래된 행부터 찾는다.
        const val DELETE_MESSAGES_SQL =
            "DELETE FROM pawbridge_animal.chatbot_messages WHERE id IN (" +
                "SELECT id FROM pawbridge_animal.chatbot_messages WHERE created_at < ? ORDER BY id LIMIT ?)"
        const val DELETE_SESSIONS_SQL =
            "DELETE FROM pawbridge_animal.chatbot_sessions WHERE id IN (" +
                "SELECT s.id FROM pawbridge_animal.chatbot_sessions s WHERE s.created_at < ? AND NOT EXISTS (" +
                "SELECT 1 FROM pawbridge_animal.chatbot_messages m WHERE m.session_id = s.id) " +
                "ORDER BY s.created_at LIMIT ?)"
        const val DELETE_BLOCK_LOGS_SQL =
            "DELETE FROM pawbridge_animal.chatbot_block_logs WHERE id IN (" +
                "SELECT id FROM pawbridge_animal.chatbot_block_logs WHERE created_at < ? ORDER BY id LIMIT ?)"
    }
}
