package com.pawbridge.backend.community.internal.homevideo.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

/** 홈 영상 목록의 버전 한 줄. 관리 화면과 자동 교체가 이 줄을 잠그고 바꾼다(관리 화면은 버전으로 충돌을 알아챈다). */
@Entity
@Table(name = "home_video_board")
class HomeVideoBoardEntity protected constructor() {

    @Id
    @Column(nullable = false)
    var id: Short? = null
        protected set

    @Column(nullable = false)
    var revision: Long = 0
        protected set

    fun changed() {
        revision += 1
    }
}

/** 자동 교체의 실행 기록 한 줄(V14). */
@Entity
@Table(name = "home_video_curation")
class HomeVideoCurationEntity protected constructor() {

    @Id
    @Column(nullable = false)
    var id: Short? = null
        protected set

    var lastSucceededAt: Instant? = null
        protected set

    var lastAttemptedAt: Instant? = null
        protected set

    fun attempted(now: Instant) {
        lastAttemptedAt = now
    }

    fun succeeded(now: Instant) {
        lastSucceededAt = now
    }
}

interface HomeVideoEntityRepository : JpaRepository<HomeVideoEntity, UUID>

interface HomeVideoBoardRepository : JpaRepository<HomeVideoBoardEntity, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT board FROM HomeVideoBoardEntity board WHERE board.id = 1")
    fun lockBoard(): HomeVideoBoardEntity?
}

interface HomeVideoCurationRepository : JpaRepository<HomeVideoCurationEntity, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT curation FROM HomeVideoCurationEntity curation WHERE curation.id = 1")
    fun lockState(): HomeVideoCurationEntity?
}
