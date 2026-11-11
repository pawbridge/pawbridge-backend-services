package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.homevideo.application.CollectHomeVideoCandidates
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.YouTubeVideoClient
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.Scheduled
import java.net.http.HttpClient
import java.time.Clock
import java.util.function.Supplier
import javax.sql.DataSource

class HomeVideoManagementConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `관리 기능이 꺼졌을 때__조립하면__HTTP 클라이언트도 관리도 작업도 만들지 않는다`(flag: String) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(HomeVideoManagementConfiguration::class.java)
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.home-videos.management-enabled=false")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(HomeVideoManagement::class.java)
                .doesNotHaveBean(YouTubeVideoClient::class.java)
                .doesNotHaveBean(HttpClient::class.java)
                .doesNotHaveBean(TaskScheduler::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `갱신 없이 관리 기능만 켰을 때__조립하면__스케줄러 없이 시작하고 연결을 열지 않는다`(
        flag: String,
    ) {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<JdbcTransactionManager>()
        val clock = Clock.systemUTC()

        justRun { jdbc.afterPropertiesSet() }
        justRun { manager.afterPropertiesSet() }

        var runner = ApplicationContextRunner().withUserConfiguration(HomeVideoManagementConfiguration::class.java)
            .withPropertyValues("pawbridge.home-videos.management-enabled=true", "youtube.api-key=synthetic-key")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean("readClock", Clock::class.java, Supplier { clock })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.jobs.refresh-enabled=false")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .hasSingleBean(HomeVideoManagement::class.java)
                .doesNotHaveBean(TaskScheduler::class.java)
            verify(exactly = 0) { manager.getTransaction(any()) }
        }
    }

    @Test
    fun `명시 갱신 작업이 있을 때__제공자 실패로 실행하면__원래 타이밍을 유지하고 다음 스케줄에 안전하게 재시도한다`() {
        // given
        val scheduled = HomeVideoManagementConfiguration.HomeVideoRefreshJob::class.java.getMethod("refresh")
            .getAnnotation(Scheduled::class.java)
        assertThat(scheduled.initialDelay).isEqualTo(60_000)
        assertThat(scheduled.fixedDelay).isEqualTo(3_600_000)
        assertThat(scheduled.scheduler).isEqualTo("homeVideoRefreshScheduler")
        val management = mockk<HomeVideoManagement>()

        every { management.refresh() } throws IllegalStateException("synthetic-private-key")

        // when, then
        assertThatCode { HomeVideoManagementConfiguration.HomeVideoRefreshJob(management).refresh() }
            .doesNotThrowAnyException()
        verify(exactly = 1) { management.refresh() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `후보 모으기가 꺼졌을 때__관리 기능만 조립하면__후보 모으기와 JPA 단위를 만들지 않는다`(flag: String) {
        // given
        var runner = managementRunner()
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.home-videos.curation-enabled=false")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(CollectHomeVideoCandidates::class.java)
                .doesNotHaveBean("homeVideoCandidateEntityManagerFactory")
        }
    }

    @Test
    fun `후보 모으기를 켰을 때__조립하면__DB 연결을 열지 않고 후보 모으기를 만든다`() {
        // given
        val source = mockk<DataSource>()
        val runner = managementRunner()
            .withPropertyValues("pawbridge.home-videos.curation-enabled=true", "gemini.api-key=synthetic-key")
            .withBean("writeDataSource", DataSource::class.java, Supplier { source })

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .hasSingleBean(CollectHomeVideoCandidates::class.java)
            verify(exactly = 0) { source.connection }
        }
    }

    private fun managementRunner(): ApplicationContextRunner {
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<JdbcTransactionManager>()
        justRun { jdbc.afterPropertiesSet() }
        justRun { manager.afterPropertiesSet() }
        return ApplicationContextRunner().withUserConfiguration(HomeVideoManagementConfiguration::class.java)
            .withPropertyValues("pawbridge.home-videos.management-enabled=true", "youtube.api-key=synthetic-key")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean("readClock", Clock::class.java, Supplier { Clock.systemUTC() })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
    }
}
