package com.pawbridge.backend.community.internal.homevideo.domain

import java.time.Duration
import java.time.Instant

/**
 * 판정(LLM)에 보내기 전에 숫자와 글자로 거르는 규칙. 기준은 2026-11-11 맛보기 검색에서 정했다.
 * 순서를 정하는 점수는 두지 않는다: YouTube 약관(III.E.4.h)이 조회수로 새 지표를 만드는 것을 막는다.
 */
object CandidateRules {
    // 쇼츠는 최대 3분이라 3분 이하는 모두 뺀다.
    private const val MIN_SECONDS = 181L
    private const val MAX_SECONDS = 30L * 60
    private const val MIN_VIEWS = 1_000L
    private val MAX_AGE: Duration = Duration.ofDays(365L * 3)

    // 분양, 판매 광고. "펫샵"은 "사지 말고 입양" 글에도 나와 넣지 않는다.
    private val SALES_WORDS = listOf("분양", "책임비", "판매", "브리더", "켄넬", "캐터리")

    // 뉴스와 방송 클립 재업로드. 채널 이름과 제목에서 찾는다(소문자 비교).
    private val BROADCASTERS = listOf("뉴스", "news", "ebs", "kbs", "mbc", "sbs", "ytn", "jtbc", "mbn", "채널a", "tv조선")

    // 구조 현장이나 학대처럼 마음 아픈 영상은 홈 분위기와 맞지 않는다.
    private val DISTRESSING_WORDS = listOf("참혹", "학대", "사체", "도살")

    fun passes(video: CandidateVideo, now: Instant): Boolean {
        if (!video.playable) {
            return false
        }
        if (video.durationSeconds < MIN_SECONDS || video.durationSeconds > MAX_SECONDS) {
            return false
        }
        if (video.viewCount < MIN_VIEWS) {
            return false
        }
        if (video.publishedAt.isBefore(now.minus(MAX_AGE))) {
            return false
        }
        val text = video.title + " " + video.description
        if (text.lowercase().contains("shorts")) {
            return false
        }
        if (SALES_WORDS.any { word -> text.contains(word) }) {
            return false
        }
        val channel = video.channelTitle.lowercase()
        if (BROADCASTERS.any { word -> channel.contains(word) }) {
            return false
        }
        val title = video.title.lowercase()
        if (title.contains("방송") || BROADCASTERS.any { word -> title.contains(word) }) {
            return false
        }
        return DISTRESSING_WORDS.none { word -> video.title.contains(word) }
    }
}
