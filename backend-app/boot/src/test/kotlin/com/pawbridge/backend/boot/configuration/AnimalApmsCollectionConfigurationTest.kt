package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchProperties
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.batch.core.Job
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.mock.env.MockEnvironment
import org.springframework.orm.jpa.JpaTransactionManager
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

class AnimalApmsCollectionConfigurationTest {
    private val context = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
        .withUserConfiguration(AnimalApmsCollectionConfiguration::class.java)

    @Test
    fun `플래그가 없을 때__컨텍스트가 시작되면__APMS 의존 빈이 필요하지 않다`() {
        // given
        val runner = context

        // when
        runner.run { application ->
            // then
            assertThat(application).hasNotFailed()
            assertThat(application)
                .doesNotHaveBean(ApmsApiClient::class.java)
                .doesNotHaveBean(Job::class.java)
                .doesNotHaveBean(JobRepository::class.java)
                .doesNotHaveBean(JobExplorer::class.java)
                .doesNotHaveBean(ApmsCollectionSchedule::class.java)
                .doesNotHaveBean("apmsJobScope")
                .doesNotHaveBean("apmsStepScope")
                .doesNotHaveBean("apmsBatchTaskExecutor")
        }
    }

    @Test
    fun `명시적으로 꺼져 있을 때__컨텍스트가 시작되면__잘못된 APMS 값을 바인딩하지 않는다`() {
        // given
        val runner = context.withPropertyValues(
            "apms.batch.schedule-enabled=false",
            "apms.api.base-url=invalid",
            "apms.batch.workers=0",
        )

        // when
        runner.run { application ->
            // then
            assertThat(application).hasNotFailed().doesNotHaveBean(ApmsScheduleSettings::class.java)
        }
    }

    @Test
    fun `APMS만 켜고 투어는 껐을 때__조립하면__연결 없이 이름 있는 메타데이터와 동기 런처가 만들어진다`() {
        // given
        val runner = context.withUserConfiguration(WriterFixture::class.java).withPropertyValues(
            "apms.batch.schedule-enabled=true", "tourapi.enabled=false", "tourapi.schedule-enabled=false",
            "apms.api.base-url=https://provider.example.test/openapi", "apms.api.service-key=fixture-key",
            "apms.batch.collection-cron=0 0 0 1 1 *", "apms.batch.stale-execution-threshold=11m",
        )

        // when
        runner
            .run { application ->
                // then
                assertThat(application).hasNotFailed()
                    .hasBean("apmsJobRepository")
                    .hasBean("apmsJobExplorer")
                    .hasBean("apmsJobLauncher")
                    .hasBean("apmsAnimalSyncJob")
                    .hasBean("apmsCollectionSchedule")
                    .doesNotHaveBean("travelCollectionJobRepository")
                    .doesNotHaveBean("petTravelCollectionJob")
                assertThat(application).hasSingleBean(KoreanSearchAnalyzer::class.java)
                val settings = application.getBean(ApmsScheduleSettings::class.java)
                assertThat(settings.workers()).isEqualTo(1)
                assertThat(settings.transactionTimeout()).isEqualTo(15)
                val animalManager = application.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
                assertThat(animalManager.defaultTimeout).isEqualTo(15)
                assertThat(application.getBean(ApmsBatchProperties::class.java).getStaleExecutionThreshold())
                    .isEqualTo(Duration.ofMinutes(11))
                val planClock = application.getBean("apmsClock", Clock::class.java)
                val metadataClock = application.getBean("apmsMetadataClock", Clock::class.java)
                assertThat(planClock.zone).isEqualTo(ZoneId.of("Asia/Seoul"))
                assertThat(metadataClock.zone).isEqualTo(ZoneId.systemDefault())
                val executor = application.getBean(AnimalApmsCollectionConfiguration.ApmsChunkExecutor::class.java)
                assertThat(executor.corePoolSize).isEqualTo(1)
                assertThat(executor.maxPoolSize).isEqualTo(1)
                assertThat(executor.threadPoolExecutor.queue.remainingCapacity()).isEqualTo(4)
                verify(exactly = 0) { application.getBean(HikariDataSource::class.java).connection }
            }
    }

    @Test
    fun `풀이 부족할 때__활성화하면__메타데이터나 제공자 호출 전에 실패한다`() {
        // given
        val runner = context.withUserConfiguration(WriterFixture::class.java).withPropertyValues(
            "apms.batch.schedule-enabled=true", "apms.batch.workers=2",
            "apms.api.base-url=https://provider.example.test", "apms.api.service-key=fixture-key",
        )

        // when
        runner
            .run { application ->
                // then
                assertThat(application.startupFailure)
                    .hasRootCauseMessage("APMS writer capacity must cover workers, session lock and metadata")
            }
    }

    @Test
    fun `작업자 수와 타임아웃 경계가 주어졌을 때__바인딩하면__양수 타임아웃과 1~4 작업자만 받아들인다`() {
        // given, when, then
        for (workers in intArrayOf(0, 5)) {
            val environment = environment().withProperty("apms.batch.workers", Integer.toString(workers))
            assertThatThrownBy { ApmsScheduleSettings(environment) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        for (workers in intArrayOf(1, 4)) {
            val settings = ApmsScheduleSettings(environment().withProperty("apms.batch.workers", Integer.toString(workers)))
            settings.requireCapacity(workers + 2)
            assertThatThrownBy { settings.requireCapacity(workers + 1) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy {
            ApmsScheduleSettings(environment().withProperty("apms.batch.step-timeout-seconds", "0"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            ApmsScheduleSettings(environment().withProperty("apms.batch.step-timeout-seconds", "9")).transactionTimeout(),
        ).isEqualTo(9)
        assertThat(ApmsBatchProperties().getStaleExecutionThreshold()).isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun `다른 검색 기록기가 주어졌을 때__조립하면__서로 다른 데이터소스 혼용을 거절한다`() {
        // given
        val source = mockk<HikariDataSource>()
        val jpa = mockk<JpaTransactionManager>()
        val otherSearch = SearchDocumentWriter(mockk<HikariDataSource>(), mockk<KoreanSearchAnalyzer>())

        every { source.maximumPoolSize } returns 3
        every { jpa.dataSource } returns source

        // when, then
        assertThatThrownBy {
            AnimalApmsCollectionConfiguration().apmsScheduleSettings(
                environment(), source, JdbcTemplate(source), jpa, otherSearch,
            )
        }.hasMessage("APMS must use the same existing writer DataSource")
    }

    private fun environment(): MockEnvironment {
        return MockEnvironment().withProperty("apms.api.base-url", "https://provider.example.test/openapi")
            .withProperty("apms.api.service-key", "fixture-key")
    }

    @Configuration(proxyBeanMethods = false)
    class WriterFixture {
        @Bean
        fun writeDataSource(): HikariDataSource {
            val source = mockk<HikariDataSource>()
            every { source.maximumPoolSize } returns 3
            justRun { source.close() }
            return source
        }

        @Bean
        fun writeJdbc(source: HikariDataSource): JdbcTemplate = JdbcTemplate(source)

        @Bean
        fun writeTransactionManager(source: HikariDataSource): JdbcTransactionManager = JdbcTransactionManager(source)

        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }
}
