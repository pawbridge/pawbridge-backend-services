package com.pawbridge.backend.community.internal.homevideo.domain

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CandidateRulesTest {
    private val now: Instant = Instant.parse("2026-11-11T00:00:00Z")

    @Test
    fun `개인 입양 브이로그일 때__거르면__통과한다`() {
        // given
        val video = video()

        // when
        val passed = CandidateRules.passes(video, now)

        // then
        assertThat(passed).isTrue()
    }

    @Test
    fun `재생할 수 없을 때__거르면__뺀다`() {
        // given
        val video = video().copy(playable = false)

        // when, then
        assertThat(CandidateRules.passes(video, now)).isFalse()
    }

    @ParameterizedTest
    @ValueSource(longs = [60, 180, 1801])
    fun `3분 이하이거나 30분을 넘을 때__거르면__뺀다`(seconds: Long) {
        // given
        val video = video().copy(durationSeconds = seconds)

        // when, then
        assertThat(CandidateRules.passes(video, now)).isFalse()
    }

    @ParameterizedTest
    @ValueSource(longs = [181, 1800])
    fun `3분 1초와 30분일 때__거르면__통과한다`(seconds: Long) {
        // given
        val video = video().copy(durationSeconds = seconds)

        // when, then
        assertThat(CandidateRules.passes(video, now)).isTrue()
    }

    @Test
    fun `조회수가 1000보다 적을 때__거르면__뺀다`() {
        // given
        val video = video().copy(viewCount = 999)

        // when, then
        assertThat(CandidateRules.passes(video, now)).isFalse()
    }

    @Test
    fun `올린 지 3년이 넘었을 때__거르면__뺀다`() {
        // given
        val video = video().copy(publishedAt = now.minus(365L * 3 + 1, ChronoUnit.DAYS))

        // when, then
        assertThat(CandidateRules.passes(video, now)).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["#Shorts", "말티즈 분양", "책임비 안내", "브리더 직영"])
    fun `쇼츠 표시나 분양 광고 말이 제목이나 설명에 있을 때__거르면__뺀다`(words: String) {
        // given
        val inTitle = video().copy(title = "입양 브이로그 $words")
        val inDescription = video().copy(description = "설명 $words")

        // when, then
        assertThat(CandidateRules.passes(inTitle, now)).isFalse()
        assertThat(CandidateRules.passes(inDescription, now)).isFalse()
    }

    @Test
    fun `뉴스나 방송사 채널일 때__거르면__뺀다`() {
        // given
        val news = video().copy(channelTitle = "ONN 뉴스")
        val broadcaster = video().copy(channelTitle = "MBC entertainment")

        // when, then
        assertThat(CandidateRules.passes(news, now)).isFalse()
        assertThat(CandidateRules.passes(broadcaster, now)).isFalse()
    }

    @Test
    fun `제목에 방송 표기가 있을 때__거르면__뺀다`() {
        // given
        val clip = video().copy(title = "[나혼자산다] 유기견 입양 MBC251226방송")

        // when, then
        assertThat(CandidateRules.passes(clip, now)).isFalse()
    }

    @Test
    fun `제목에 마음 아픈 말이 있을 때__거르면__빼지만 설명에만 있으면 남긴다`() {
        // given
        val inTitle = video().copy(title = "참혹한 현장에서 구조된 강아지")
        val inDescription = video().copy(description = "학대받던 아이였지만 지금은 행복해요")

        // when, then
        assertThat(CandidateRules.passes(inTitle, now)).isFalse()
        assertThat(CandidateRules.passes(inDescription, now)).isTrue()
    }

    private fun video(): CandidateVideo =
        CandidateVideo(
            "AbCdEfGhI_1",
            "UCchannel000000000000001",
            "우리 하모",
            "신혼부부 유기견 입양 브이로그",
            "포인핸드에서 만난 아이와 첫 산책",
            600,
            12_000,
            now.minus(100, ChronoUnit.DAYS),
            true,
        )
}
