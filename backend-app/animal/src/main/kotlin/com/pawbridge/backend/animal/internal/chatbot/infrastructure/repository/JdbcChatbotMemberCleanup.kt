package com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository

import org.springframework.jdbc.core.JdbcOperations

/**
 * 회원 삭제 때 그 회원이 시작한 AI 입양 도우미 대화에서 회원 번호만 지운다. 대화 원문은 보관 기간까지 남고
 * 익명 쿠키 번호로만 묶인다. 같은 회원으로 다시 실행해도 결과가 같아 회원 삭제 재시도에 그대로 쓸 수 있다.
 */
class JdbcChatbotMemberCleanup(private val jdbc: JdbcOperations) {

    /** 회원 번호를 지운 세션 수를 돌려준다. */
    fun forget(memberId: Long): Int {
        return jdbc.update(FORGET_SQL, memberId)
    }

    private companion object {
        const val FORGET_SQL = "UPDATE pawbridge_animal.chatbot_sessions SET member_id = NULL WHERE member_id = ?"
    }
}
