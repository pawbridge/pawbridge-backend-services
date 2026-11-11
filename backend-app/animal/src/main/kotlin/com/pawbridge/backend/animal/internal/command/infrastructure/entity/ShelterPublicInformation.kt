package com.pawbridge.backend.animal.internal.command.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 보호소 정보 수집기(shelterDirectoryJob)가 공공데이터에서 모은 보호소 공공정보.
 *
 * 읽기 전용이다(@Immutable). 행을 쓰는 곳은 수집기(ShelterDirectoryStore) 하나뿐이고 저장소도 조회만 연다.
 * details 키는 수집기의 ShelterPublicDetails가 정한다: phone, weekdayOpen, weekdayClose, weekendOpen,
 * weekendClose, closedDays, latitude, longitude, sourceUpdatedDate.
 */
@Entity
@Immutable
@Table(name = "shelter_public_information", schema = "pawbridge_animal")
class ShelterPublicInformation protected constructor() {

    // 보호소 기본키와 같은 값(shelters.id 외래키)
    @Id
    @Column(name = "shelter_id")
    var shelterId: Long? = null
        protected set

    // jsonb 원문을 키와 값 그대로 읽는다(글자는 String, 좌표는 숫자).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb")
    var details: Map<String, Any?>? = null
        protected set
}
