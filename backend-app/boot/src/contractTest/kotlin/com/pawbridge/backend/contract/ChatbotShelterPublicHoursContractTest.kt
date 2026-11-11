package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ShelterPublicHours
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.JdbcShelterPublicHoursReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 챗봇이 실제 shelter_public_information 표에서 운영시간과 휴무일을 읽는지 본다. */
class ChatbotShelterPublicHoursContractTest : AnimalRepositoryContractBase() {
    @Autowired
    private lateinit var mapper: ObjectMapper

    private lateinit var reader: JdbcShelterPublicHoursReader

    @BeforeEach
    fun seed() {
        reader = JdbcShelterPublicHoursReader(JdbcTemplate(writeSource), mapper)
        seedShelter(1)
        seedShelter(2)
    }

    @Test
    fun `수집된 행이 있는 보호소와 없는 보호소일 때__읽으면__있는 쪽만 운영시간 칸을 채운다`() {
        // given
        execute(
            "INSERT INTO pawbridge_animal.shelter_public_information(shelter_id, details, collected_at) VALUES " +
                "(1, '{\"phone\":\"02-111-2222\",\"weekdayOpen\":\"09:00\",\"weekdayClose\":\"18:00\"," +
                "\"closedDays\":\"일요일+공휴일\",\"latitude\":37.5,\"longitude\":127.0,\"sourceUpdatedDate\":\"2026-01-02\"}'::jsonb, " +
                "'2026-11-11 11:45:00')",
        )

        // when
        val collected = reader.find(1)
        val missing = reader.find(2)

        // then
        assertThat(collected).isEqualTo(ShelterPublicHours("09:00", "18:00", null, null, "일요일+공휴일", "2026-01-02"))
        assertThat(missing).isNull()
    }
}
