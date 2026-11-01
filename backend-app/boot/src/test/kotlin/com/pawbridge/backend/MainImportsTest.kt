package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.AnimalApmsCollectionConfiguration
import com.pawbridge.backend.boot.configuration.AnimalGalleryFeedConfiguration
import com.pawbridge.backend.boot.configuration.AnimalImageConfiguration
import com.pawbridge.backend.boot.configuration.AnimalPhotoArchiveConfiguration
import com.pawbridge.backend.boot.configuration.AnimalSearchProjectionConfiguration
import com.pawbridge.backend.boot.configuration.AnimalTravelCollectionConfiguration
import com.pawbridge.backend.boot.configuration.ShelterCommandConfiguration
import com.pawbridge.backend.boot.configuration.ShelterDirectoryConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.context.annotation.Import

/**
 * The application only gets a feature when it imports that feature's configuration, and a configuration imported
 * twice would register its beans twice. Every feature configuration is listed once here instead of in the test
 * of each feature.
 */
class MainImportsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    fun `기능 설정이 주어졌을 때__애플리케이션 임포트를 읽으면__그 설정을 정확히 한 번 등록한다`(configuration: Class<*>) {
        // given
        val imports = PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java }

        // when
        val registrations = imports.count { it == configuration }

        // then
        assertThat(registrations).isEqualTo(1)
    }

    companion object {
        @JvmStatic
        fun configurations(): List<Class<*>> = listOf(
            AnimalApmsCollectionConfiguration::class.java,
            AnimalGalleryFeedConfiguration::class.java,
            AnimalImageConfiguration::class.java,
            AnimalPhotoArchiveConfiguration::class.java,
            AnimalSearchProjectionConfiguration::class.java,
            AnimalTravelCollectionConfiguration::class.java,
            ShelterCommandConfiguration::class.java,
            ShelterDirectoryConfiguration::class.java,
        )
    }
}
