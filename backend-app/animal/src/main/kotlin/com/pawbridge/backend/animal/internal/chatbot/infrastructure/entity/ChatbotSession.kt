package com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "chatbot_sessions")
class ChatbotSession protected constructor() : BaseTimeEntity() {

    @Id
    @Column(length = 36)
    var id: String? = null
        protected set

    @Column(nullable = false, length = 36)
    var anonymousSessionId: String? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "animal_id", nullable = false)
    var animal: Animal? = null
        protected set

    /** 로그인한 상태로 대화를 시작했을 때의 회원 번호. 회원 삭제 때 NULL로 지운다. */
    @Column(name = "member_id")
    var memberId: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", length = 10)
    var deviceType: ChatbotDeviceType? = null
        protected set

    constructor(
        anonymousSessionId: String?,
        animal: Animal?,
        memberId: Long? = null,
        deviceType: ChatbotDeviceType? = null,
    ) : this() {
        this.anonymousSessionId = anonymousSessionId
        this.animal = animal
        this.memberId = memberId
        this.deviceType = deviceType
    }

    @PrePersist
    protected fun generateId() {
        if (id == null) {
            id = UUID.randomUUID().toString()
        }
    }
}
