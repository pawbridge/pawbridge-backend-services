package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Persistable

/**
 * 자동 교체가 쓰는 홈 영상 줄. 관리 화면은 같은 표를 기존 JDBC 코드로 쓰고, 두 쪽 모두 목록 잠금(home_video_board) 안에서만 바꾼다.
 * 새 줄은 항상 INSERT로 저장한다([isNew]).
 */
@Entity
@Table(name = "home_videos")
class HomeVideoEntity protected constructor() : Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false)
    var rowId: UUID? = null
        protected set

    @Column(nullable = false, length = 11)
    var videoId: String? = null
        protected set

    @Column(nullable = false)
    var published: Boolean = false
        protected set

    @Column(nullable = false)
    var position: Int = 0
        protected set

    @Column(nullable = false)
    var createdAt: Instant? = null
        protected set

    @Column(length = 500)
    var title: String? = null
        protected set

    @Column(length = 300)
    var channelTitle: String? = null
        protected set

    @Column(length = 2048)
    var thumbnailUrl: String? = null
        protected set

    var durationSeconds: Long? = null
        protected set

    @Column(nullable = false)
    var available: Boolean = false
        protected set

    var checkedAt: Instant? = null
        protected set

    @Transient
    private var fresh: Boolean = false

    override fun getId(): UUID? = rowId

    override fun isNew(): Boolean = fresh

    @PostLoad
    @PostPersist
    protected fun markStored() {
        fresh = false
    }

    fun toHomeVideo(): HomeVideo =
        HomeVideo(
            rowId!!,
            videoId!!,
            published,
            position,
            createdAt!!,
            title,
            channelTitle,
            thumbnailUrl,
            durationSeconds,
            available,
            checkedAt,
        )

    companion object {
        /** 방금 YouTube에서 재생을 확인한 영상을 게시 상태로 올린다. */
        fun newlyPublished(metadata: Metadata, position: Int, now: Instant): HomeVideoEntity {
            val video = HomeVideoEntity()
            video.rowId = UUID.randomUUID()
            video.videoId = metadata.videoId
            video.published = true
            video.position = position
            video.createdAt = now
            video.title = metadata.title
            video.channelTitle = metadata.channelTitle
            video.thumbnailUrl = metadata.thumbnailUrl
            video.durationSeconds = metadata.durationSeconds
            video.available = true
            video.checkedAt = now
            video.fresh = true
            return video
        }
    }
}
