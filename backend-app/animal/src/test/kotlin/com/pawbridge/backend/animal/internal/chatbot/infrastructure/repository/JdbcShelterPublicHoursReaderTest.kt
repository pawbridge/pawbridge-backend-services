package com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ShelterPublicHours
import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterPublicDetails
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet

class JdbcShelterPublicHoursReaderTest {
    private val jdbc = mockk<JdbcOperations>()
    private val mapper = ObjectMapper()
    private val reader = JdbcShelterPublicHoursReader(jdbc, mapper)

    @Test
    fun `수집기가 저장한 모양의 행일 때__읽으면__같은 키 이름으로 운영시간 칸을 채운다`() {
        // given
        // 수집기가 실제로 쓰는 변환(ShelterPublicDetails)으로 만든 JSON이라 키 이름이 어긋나면 이 시험이 깨진다.
        val details = ShelterPublicDetails.details(
            mapOf(
                "careTel" to "000-0000-0000",
                "weekOprStime" to "09:00",
                "weekOprEtime" to "18:00",
                "weekendOprStime" to "10:00",
                "weekendOprEtime" to "15:00",
                "closeDay" to "일요일+공휴일",
                "lat" to "37.5",
                "lng" to "127.0",
                "dataStdDt" to "2026-01-02",
            ),
        )
        rows(mapper.writeValueAsString(details))

        // when
        val hours = reader.find(7L)

        // then
        assertEquals(ShelterPublicHours("09:00", "18:00", "10:00", "15:00", "일요일+공휴일", "2026-01-02"), hours)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<ShelterPublicHours?>>(), 7L) }
    }

    @Test
    fun `글자가 아닌 값이나 빠진 키가 있을 때__읽으면__그 칸만 비운다`() {
        // given
        rows("""{"weekdayOpen": 900, "weekdayClose": null, "closedDays": "일요일"}""")

        // when
        val hours = reader.find(7L)

        // then
        assertEquals(ShelterPublicHours(null, null, null, null, "일요일", null), hours)
    }

    @Test
    fun `객체가 아닌 JSON일 때__읽으면__null이다`() {
        // given
        rows("""["09:00"]""")

        // when
        val hours = reader.find(7L)

        // then
        assertNull(hours)
    }

    @Test
    fun `수집된 행이 없을 때__읽으면__null이다`() {
        // given
        rows()

        // when
        val hours = reader.find(7L)

        // then
        assertNull(hours)
    }

    /** 주어진 details 글마다 한 행씩 돌려주는 JDBC를 흉내 낸다. */
    private fun rows(vararg details: String) {
        every { jdbc.query(any<String>(), any<RowMapper<ShelterPublicHours?>>(), any<Long>()) } answers {
            val rowMapper = secondArg<RowMapper<ShelterPublicHours?>>()
            details.mapIndexed { index, value ->
                val row = mockk<ResultSet>()
                every { row.getString("details") } returns value
                rowMapper.mapRow(row, index)
            }
        }
    }
}
