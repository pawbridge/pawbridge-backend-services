package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.domain.CandidateStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import org.springframework.data.domain.Persistable

/**
 * 새로 만든 줄은 항상 INSERT로 저장한다([isNew]). ID가 정해진 엔티티라 그냥 두면 merge가 되어,
 * 두 작업이 겹칠 때 "사용함" 줄을 "예비"로 덮어쓸 수 있다. INSERT면 기본 키가 막아 늦은 쪽이 실패한다.
 */
@Entity
@Table(name = "home_video_candidates")
class HomeVideoCandidate protected constructor() : Persistable<String> {

    @Id
    @Column(nullable = false, length = 11)
    var videoId: String? = null
        protected set

    // 관리자가 손으로 올렸다가 교체된 영상은 채널 ID를 모른다.
    @Column(length = 64)
    var channelId: String? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: CandidateStatus? = null
        protected set

    @Column(nullable = false)
    var searchRank: Int? = null
        protected set

    @Column(nullable = false)
    var checkedAt: Instant? = null
        protected set

    @Transient
    private var fresh: Boolean = false

    override fun getId(): String? = videoId

    override fun isNew(): Boolean = fresh

    @PostLoad
    @PostPersist
    protected fun markStored() {
        fresh = false
    }

    /** 홈에 올렸거나 홈에서 뺀 영상: 다시 넣지 않는다. */
    fun markUsed(now: Instant) {
        status = CandidateStatus.USED
        checkedAt = now
    }

    /** YouTube에서 아직 있는지 다시 확인했다(약관의 30일 기한). */
    fun confirm(now: Instant) {
        checkedAt = now
    }

    constructor(
        videoId: String,
        channelId: String?,
        status: CandidateStatus,
        searchRank: Int,
        checkedAt: Instant,
    ) : this() {
        this.videoId = videoId
        this.channelId = channelId
        this.status = status
        this.searchRank = searchRank
        this.checkedAt = checkedAt
        this.fresh = true
    }
}
