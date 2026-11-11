package com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ShelterPublicHours
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper

/**
 * 수집기가 저장한 details(jsonb)에서 운영시간과 휴무일 칸만 읽는다.
 * 키 이름은 수집기의 ShelterPublicDetails와 같다(weekdayOpen, weekdayClose, weekendOpen, weekendClose,
 * closedDays, sourceUpdatedDate). 호출하는 쪽의 읽기 트랜잭션 안에서 부른다.
 */
class JdbcShelterPublicHoursReader(
    private val jdbc: JdbcOperations,
    private val mapper: ObjectMapper,
) : ShelterPublicHoursReader {

    override fun find(shelterId: Long): ShelterPublicHours? {
        val rows = jdbc.query(
            SQL,
            RowMapper<ShelterPublicHours?> { row, _ -> toHours(row.getString("details")) },
            shelterId,
        )
        return rows.firstOrNull()
    }

    private fun toHours(details: String?): ShelterPublicHours? {
        if (details == null) {
            return null
        }
        val node = mapper.readTree(details)
        if (!node.isObject) {
            return null
        }
        return ShelterPublicHours(
            text(node, "weekdayOpen"),
            text(node, "weekdayClose"),
            text(node, "weekendOpen"),
            text(node, "weekendClose"),
            text(node, "closedDays"),
            text(node, "sourceUpdatedDate"),
        )
    }

    // 글자가 아닌 값(숫자, null)은 원문 모양이 아니므로 없는 것으로 본다.
    private fun text(node: JsonNode, key: String): String? {
        val value = node.get(key)
        if (value == null || !value.isTextual) {
            return null
        }
        return value.asText()
    }

    private companion object {
        private const val SQL =
            "SELECT details FROM pawbridge_animal.shelter_public_information WHERE shelter_id = ?"
    }
}
