package com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity

/**
 * 대화를 시작한 기기 종류. 분석에 쓸 만큼만 나누고 User-Agent 원문은 남기지 않는다.
 * 태블릿은 휴대기기로 본다. 데스크톱 모드로 요청하는 iPad처럼 PC와 같은 User-Agent를 보내면 DESKTOP이 된다.
 */
enum class ChatbotDeviceType {
    MOBILE,
    DESKTOP,
    ;

    companion object {
        private val MOBILE_AGENT = Regex("Mobi|Android|iPhone|iPad|iPod", RegexOption.IGNORE_CASE)

        /** User-Agent가 없거나 비어 있으면 알 수 없으므로 null이다. */
        @JvmStatic
        fun fromUserAgent(userAgent: String?): ChatbotDeviceType? {
            if (userAgent == null || userAgent.isBlank()) {
                return null
            }
            return if (MOBILE_AGENT.containsMatchIn(userAgent)) MOBILE else DESKTOP
        }
    }
}
