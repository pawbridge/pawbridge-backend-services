package com.pawbridge.backend.community.internal.homevideo.domain

import java.time.Instant

/**
 * 자동 검색으로 만난 영상 하나. 규칙과 판정에 필요한 칸만 담는다.
 * 저장할 때는 영상 ID와 채널 ID만 남기고 제목과 설명은 버린다.
 */
@JvmRecord
data class CandidateVideo(
    val videoId: String,
    val channelId: String,
    val channelTitle: String,
    val title: String,
    val description: String,
    val durationSeconds: Long,
    val viewCount: Long,
    val publishedAt: Instant,
    val playable: Boolean,
)
