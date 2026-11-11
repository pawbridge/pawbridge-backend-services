package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

/**
 * 공고를 올린 보호소 정보. Python 에이전트 프롬프트의 [보호소 정보]에 들어간다(고정 흐름은 쓰지 않는다).
 *
 * 칸 이름은 Python `ShelterContext`와 같다. 담당자 이름과 이메일은 보내지 않는다.
 * noticeEndDate는 ISO 날짜 문자열(예: 2026-11-20)이다.
 */
@JvmRecord
data class ShelterChatbotContext(
    val shelterName: String?,
    val shelterPhone: String?,
    val shelterAddress: String?,
    val noticeEndDate: String?,
    val adoptionProcedure: String?,
    val operatingHours: String?,
)
