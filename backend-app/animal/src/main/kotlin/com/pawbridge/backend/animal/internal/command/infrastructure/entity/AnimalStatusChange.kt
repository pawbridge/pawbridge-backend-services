package com.pawbridge.backend.animal.internal.command.infrastructure.entity

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import java.time.Instant

/**
 * 관심 동물 알림용 마지막 상태 변경. 동물마다 마지막 1건만 남기고 이전 변경은 남기지 않는다.
 * 상태를 바꾸는 모든 경로(보호소 수정, APMS 동기화 일괄 UPDATE)가 이 규칙 하나를 쓴다.
 */
@JvmRecord
data class AnimalStatusChange(val previousStatus: AnimalStatus, val changedAt: Instant) {
    companion object {
        /** 같은 상태로의 갱신과 미상(APMS 문자열을 해석하지 못한 값)으로의 변화는 변경이 아니다. */
        fun between(current: AnimalStatus?, next: AnimalStatus?, at: Instant): AnimalStatusChange? {
            if (current == null || next == null || next == current || next == AnimalStatus.UNKNOWN) {
                return null
            }
            return AnimalStatusChange(current, at)
        }
    }
}
