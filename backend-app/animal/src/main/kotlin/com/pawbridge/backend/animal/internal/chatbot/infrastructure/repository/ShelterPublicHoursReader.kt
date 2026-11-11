package com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ShelterPublicHours

/**
 * 보호소 공공정보(shelter_public_information) 중 운영시간과 휴무일을 읽는 창구.
 * 챗봇은 보호소 패키지 내부를 직접 쓰지 않고 이 창구로만 읽는다.
 */
fun interface ShelterPublicHoursReader {
    /** 수집된 행이 없으면 null. */
    fun find(shelterId: Long): ShelterPublicHours?
}
