package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.application.CurrentHomeVideos
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class JdbcHomeVideoRepository(private val jdbc: JdbcTemplate) : CurrentHomeVideos {

    override fun videoIds(): Set<String> = list().map { video -> video.videoId }.toSet()

    fun lockBoard(): Long =
        jdbc.queryForObject(
            "SELECT revision FROM pawbridge_community.home_video_board WHERE id=1 FOR UPDATE",
            Long::class.javaObjectType,
        )!!

    fun revision(): Long =
        jdbc.queryForObject(
            "SELECT revision FROM pawbridge_community.home_video_board WHERE id=1",
            Long::class.javaObjectType,
        )!!

    fun changed() {
        jdbc.update("UPDATE pawbridge_community.home_video_board SET revision=revision+1 WHERE id=1")
    }

    fun list(): List<HomeVideo> =
        jdbc.query(
            "SELECT * FROM pawbridge_community.home_videos ORDER BY position, created_at, id",
            RowMapper { row, _ -> map(row) },
        )

    fun insert(id: UUID, videoId: String, published: Boolean, position: Int, now: Instant) {
        jdbc.update(
            """
            INSERT INTO pawbridge_community.home_videos(id,video_id,published,position,created_at)
            VALUES (?,?,?,?,?)
            """.trimIndent() + "\n",
            id,
            videoId,
            published,
            position,
            Timestamp.from(now),
        )
    }

    fun selection(id: UUID, videoId: String, published: Boolean) {
        jdbc.update(
            "UPDATE pawbridge_community.home_videos SET video_id=?,published=? WHERE id=?",
            videoId,
            published,
            id,
        )
    }

    fun publication(id: UUID, published: Boolean) {
        jdbc.update("UPDATE pawbridge_community.home_videos SET published=? WHERE id=?", published, id)
    }

    fun position(id: UUID, position: Int) {
        jdbc.update("UPDATE pawbridge_community.home_videos SET position=? WHERE id=?", position, id)
    }

    fun metadata(id: UUID, metadata: Metadata?, checkedAt: Instant) {
        if (metadata == null || !metadata.available) {
            jdbc.update(
                """
                UPDATE pawbridge_community.home_videos SET title=NULL,channel_title=NULL,thumbnail_url=NULL,
                duration_seconds=NULL,available=false,checked_at=? WHERE id=?
                """.trimIndent() + "\n",
                Timestamp.from(checkedAt),
                id,
            )
        } else {
            jdbc.update(
                """
                UPDATE pawbridge_community.home_videos SET title=?,channel_title=?,thumbnail_url=?,
                duration_seconds=?,available=true,checked_at=? WHERE id=?
                """.trimIndent() + "\n",
                metadata.title,
                metadata.channelTitle,
                metadata.thumbnailUrl,
                metadata.durationSeconds,
                Timestamp.from(checkedAt),
                id,
            )
        }
    }

    fun expire(cutoff: Instant): Int =
        // Clear provider metadata before its 30-day deadline, but retain user-selected ID/settings.
        jdbc.update(
            """
            UPDATE pawbridge_community.home_videos SET title=NULL,channel_title=NULL,thumbnail_url=NULL,
            duration_seconds=NULL,available=false,checked_at=NULL WHERE checked_at <= ?
            """.trimIndent() + "\n",
            Timestamp.from(cutoff),
        )

    private fun map(row: ResultSet): HomeVideo {
        val checkedAt = row.getTimestamp("checked_at")
        return HomeVideo(
            row.getObject("id", UUID::class.java),
            row.getString("video_id"),
            row.getBoolean("published"),
            row.getInt("position"),
            row.getTimestamp("created_at").toInstant(),
            row.getString("title"),
            row.getString("channel_title"),
            row.getString("thumbnail_url"),
            row.getObject("duration_seconds") as Long?,
            row.getBoolean("available"),
            checkedAt?.toInstant(),
        )
    }
}
