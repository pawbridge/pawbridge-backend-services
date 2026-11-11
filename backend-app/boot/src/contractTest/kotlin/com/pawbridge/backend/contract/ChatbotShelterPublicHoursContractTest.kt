package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ShelterPublicHours
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterPublicInformationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 챗봇이 실제 shelter_public_information 표(jsonb)를 읽기 전용 JPA 엔티티로 읽는지 본다. */
// Animal JPA 저장소는 JPA 계약 시험 컨텍스트(JpaContractBase)에만 조립된다.
class ChatbotShelterPublicHoursContractTest : JpaContractBase() {
    @Autowired
    @Qualifier("animalCommandRepositoryFactory")
    private lateinit var repositories: JpaRepositoryFactory

    @Autowired
    @Qualifier("animalCommandJpaTransactionManager")
    private lateinit var jpaManager: JpaTransactionManager

    private lateinit var information: ShelterPublicInformationRepository

    @BeforeEach
    fun seed() {
        information = repositories.getRepository(ShelterPublicInformationRepository::class.java)
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
        val reads = TransactionTemplate(jpaManager)
        reads.isReadOnly = true

        // when
        val collected = reads.execute {
            information.findById(1).map { row -> ShelterPublicHours.fromDetails(row.details) }.orElse(null)
        }
        val missing = reads.execute { information.findById(2).isPresent }

        // then
        assertThat(collected).isEqualTo(ShelterPublicHours("09:00", "18:00", null, null, "일요일+공휴일", "2026-01-02"))
        assertThat(missing).isFalse()
    }
}
