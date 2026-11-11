package com.pawbridge.backend.animal.internal.command.infrastructure.repository

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.ShelterPublicInformation
import org.springframework.data.repository.Repository
import java.util.Optional

/** 보호소 공공정보 읽기 전용 저장소. 저장과 삭제는 수집기만 하므로 조회 메서드만 연다. */
interface ShelterPublicInformationRepository : Repository<ShelterPublicInformation, Long> {
    fun findById(shelterId: Long): Optional<ShelterPublicInformation>
}
