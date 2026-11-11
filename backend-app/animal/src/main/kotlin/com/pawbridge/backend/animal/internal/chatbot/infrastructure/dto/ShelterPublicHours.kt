package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

import java.time.LocalDate

/**
 * 보호소 정보 수집기(shelterDirectoryJob)가 공공데이터에서 모은 운영시간과 휴무일.
 *
 * 값은 공공데이터 원문 그대로라 빈 값이나 뜻을 알 수 없는 값(휴무일 "0", 시각 ":")이 섞여 있다.
 * 챗봇에 넣을 글은 [describe]가 거른 뒤에 만든다.
 */
data class ShelterPublicHours(
    val weekdayOpen: String?,
    val weekdayClose: String?,
    val weekendOpen: String?,
    val weekendClose: String?,
    val closedDays: String?,
    val sourceUpdatedDate: String?,
) {
    /**
     * 챗봇 프롬프트의 운영시간 칸에 넣을 한 줄 글.
     * 예: "평일 09:00~18:00, 휴무 토요일, 일요일 (공공데이터 2026-01-02 기준, 방문 전 전화 확인 필요)"
     * 쓸 수 있는 값이 하나도 없으면 null이다.
     */
    fun describe(): String? {
        val parts = ArrayList<String>()

        val weekday = timeRange(weekdayOpen, weekdayClose)
        if (weekday != null) {
            parts.add("평일 $weekday")
        }

        val weekend = timeRange(weekendOpen, weekendClose)
        if (weekend != null) {
            parts.add("주말 $weekend")
        }

        val closed = closedDayText(closedDays)
        if (closed != null) {
            parts.add(closed)
        }

        if (parts.isEmpty()) {
            return null
        }

        val date = sourceDate(sourceUpdatedDate)
        val basis = if (date != null) "공공데이터 $date 기준" else "공공데이터 기준"
        return parts.joinToString(", ") + " ($basis, 방문 전 전화 확인 필요)"
    }

    private companion object {
        // 원문 시각은 "09:00" 모양이다. 자정 마감을 적는 곳이 있을 수 있어 24:00도 받는다.
        private val TIME = Regex("""([01]\d|2[0-3]):[0-5]\d|24:00""")

        // 원문 휴무일은 "토요일+일요일+공휴일" 모양이다. 숫자("0", "1+15+10")는 뜻을 알 수 없어 버린다.
        private val DAY_NAMES = setOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일", "공휴일")
        private const val NO_HOLIDAY = "연중무휴"

        /** 시작과 끝이 모두 시각 모양이고 서로 다를 때만 "09:00~18:00"을 돌려준다. */
        fun timeRange(open: String?, close: String?): String? {
            val start = open?.trim()
            val end = close?.trim()
            if (start == null || end == null) {
                return null
            }
            if (!TIME.matches(start) || !TIME.matches(end)) {
                return null
            }
            if (start == end) {
                return null
            }
            return "$start~$end"
        }

        /** 요일과 공휴일로만 이뤄진 값이면 "휴무 토요일, 일요일"을, 연중무휴면 그대로 돌려준다. */
        fun closedDayText(value: String?): String? {
            val text = value?.trim()
            if (text.isNullOrEmpty()) {
                return null
            }
            if (text == NO_HOLIDAY) {
                return NO_HOLIDAY
            }
            val days = text.split("+").map { day -> day.trim() }
            if (days.any { day -> day !in DAY_NAMES }) {
                return null
            }
            return "휴무 " + days.distinct().joinToString(", ")
        }

        /** ISO 날짜(2026-01-02)일 때만 돌려준다. */
        fun sourceDate(value: String?): String? {
            val text = value?.trim()
            if (text.isNullOrEmpty()) {
                return null
            }
            return try {
                LocalDate.parse(text).toString()
            } catch (ignored: RuntimeException) {
                null
            }
        }
    }
}
