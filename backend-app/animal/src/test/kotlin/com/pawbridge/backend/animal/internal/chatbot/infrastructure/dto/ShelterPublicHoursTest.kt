package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class ShelterPublicHoursTest {

    @Test
    fun `평일 시각과 휴무일과 기준일이 있을 때__글로 만들면__기준일과 전화 확인 안내를 붙인다`() {
        // given
        val hours = ShelterPublicHours("09:00", "18:00", null, null, "토요일+일요일+공휴일", "2026-01-02")

        // when
        val text = hours.describe()

        // then
        assertEquals("평일 09:00~18:00, 휴무 토요일, 일요일, 공휴일 (공공데이터 2026-01-02 기준, 방문 전 전화 확인 필요)", text)
    }

    @Test
    fun `주말 시각과 연중무휴가 있고 기준일이 없을 때__글로 만들면__기준일 없이 공공데이터 기준이라고 적는다`() {
        // given
        val hours = ShelterPublicHours(" 10:00 ", "17:00", "10:00", "15:00", " 연중무휴 ", null)

        // when
        val text = hours.describe()

        // then
        assertEquals("평일 10:00~17:00, 주말 10:00~15:00, 연중무휴 (공공데이터 기준, 방문 전 전화 확인 필요)", text)
    }

    @ParameterizedTest
    @MethodSource("junkOnly")
    fun `쓸 수 있는 값이 하나도 없을 때__글로 만들면__null이다`(hours: ShelterPublicHours) {
        // given
        // when
        val text = hours.describe()

        // then
        assertNull(text)
    }

    @Test
    fun `시각 하나만 잘못됐을 때__글로 만들면__그 구간만 빼고 나머지는 쓴다`() {
        // given
        val hours = ShelterPublicHours("09:00", "18:00", ":", ":", "0", "날짜아님")

        // when
        val text = hours.describe()

        // then
        assertEquals("평일 09:00~18:00 (공공데이터 기준, 방문 전 전화 확인 필요)", text)
    }

    @Test
    fun `휴무일 요일이 겹칠 때__글로 만들면__한 번만 적는다`() {
        // given
        val hours = ShelterPublicHours(null, null, null, null, "일요일+공휴일+일요일", "2025-12-31")

        // when
        val text = hours.describe()

        // then
        assertEquals("휴무 일요일, 공휴일 (공공데이터 2025-12-31 기준, 방문 전 전화 확인 필요)", text)
    }

    companion object {
        @JvmStatic
        fun junkOnly(): Stream<Arguments> = Stream.of(
            // 모두 비어 있음
            Arguments.of(ShelterPublicHours(null, null, null, null, null, "2026-01-02")),
            // 공공데이터에 실제로 있는 뜻 모를 값: 시각 ":", 휴무일 "0"
            Arguments.of(ShelterPublicHours(":", ":", ":", ":", "0", "2026-01-02")),
            // 시작과 끝이 같은 시각, 끝만 있는 시각
            Arguments.of(ShelterPublicHours("09:00", "09:00", null, "18:00", "", "2026-01-02")),
            // 시각 모양이 아님, 숫자가 섞인 휴무일
            Arguments.of(ShelterPublicHours("9시", "18시", "25:00", "26:00", "1+15+10", null)),
            // 요일 이름이 아닌 글이 섞인 휴무일
            Arguments.of(ShelterPublicHours(null, null, null, null, "일요일+격주 토요일", null)),
        )
    }
}
