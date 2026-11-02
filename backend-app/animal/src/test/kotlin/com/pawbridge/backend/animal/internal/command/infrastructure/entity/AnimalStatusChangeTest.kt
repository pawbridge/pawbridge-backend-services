package com.pawbridge.backend.animal.internal.command.infrastructure.entity

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AnimalStatusChangeTest {
    private val at = Instant.parse("2026-11-02T03:00:00Z")

    @Test
    fun `다른 상태로 바뀔 때__변경을 판단하면__직전 상태와 시각을 남긴다`() {
        // given
        // when
        val change = AnimalStatusChange.between(AnimalStatus.PROTECT, AnimalStatus.ADOPTED, at)

        // then
        assertThat(change).isEqualTo(AnimalStatusChange(AnimalStatus.PROTECT, at))
    }

    @Test
    fun `같은 상태이거나 미상으로 가거나 상태가 없을 때__변경을 판단하면__변경이 아니다`() {
        // given
        // when
        // then
        assertThat(AnimalStatusChange.between(AnimalStatus.PROTECT, AnimalStatus.PROTECT, at)).isNull()
        assertThat(AnimalStatusChange.between(AnimalStatus.PROTECT, AnimalStatus.UNKNOWN, at)).isNull()
        assertThat(AnimalStatusChange.between(null, AnimalStatus.PROTECT, at)).isNull()
        assertThat(AnimalStatusChange.between(AnimalStatus.PROTECT, null, at)).isNull()
    }

    @Test
    fun `미상에서 알려진 상태로 돌아올 때__변경을 판단하면__미상을 직전 상태로 남긴다`() {
        // given
        // Only the latest change is kept, so the status before UNKNOWN is not known here.

        // when
        val change = AnimalStatusChange.between(AnimalStatus.UNKNOWN, AnimalStatus.PROTECT, at)

        // then
        assertThat(change).isEqualTo(AnimalStatusChange(AnimalStatus.UNKNOWN, at))
    }
}
