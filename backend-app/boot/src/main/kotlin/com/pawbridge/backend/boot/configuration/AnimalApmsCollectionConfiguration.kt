package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchExecutionRecovery
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchProperties
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsSyncPlanFactory
import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.web.ApmsBatchController
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.springframework.batch.core.Job
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.repository.dao.DefaultExecutionContextSerializer
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean
import org.springframework.batch.core.scope.JobScope
import org.springframework.batch.core.scope.StepScope
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * APMS-only composition; the existing Animal writer remains the sole EMF and database pool owner.
 * Assembled for the schedule or the original manual API; each entry point keeps its own flag.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AnimalApmsCollectionConfiguration.ApmsEntryPoint::class)
@EnableConfigurationProperties
@Import(AnimalApmsJobConfiguration::class, AnimalJpaWriterConfiguration::class)
class AnimalApmsCollectionConfiguration {
    @Bean
    fun apmsScheduleSettings(
        environment: Environment,
        @Qualifier("writeDataSource") source: HikariDataSource,
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("animalCommandJpaTransactionManager") animalManager: JpaTransactionManager,
        @Qualifier("animalCommandSearch") search: SearchDocumentWriter,
    ): ApmsScheduleSettings {
        val settings = ApmsScheduleSettings(environment)
        settings.requireCapacity(source.maximumPoolSize)
        if (jdbc.dataSource !== source || animalManager.dataSource !== source || search.dataSource() !== source) {
            throw IllegalArgumentException("APMS must use the same existing writer DataSource")
        }
        return settings
    }

    @Bean
    @ConfigurationProperties(prefix = "apms.batch")
    fun apmsBatchProperties(): ApmsBatchProperties {
        return ApmsBatchProperties()
    }

    @Bean
    fun apmsClock(): Clock {
        return Clock.system(ZoneId.of("Asia/Seoul"))
    }

    @Bean
    fun apmsMetadataClock(): Clock {
        return Clock.systemDefaultZone()
    }

    @Bean(destroyMethod = "close")
    fun apmsApiClient(settings: ApmsScheduleSettings, mapper: ObjectMapper, meters: MeterRegistry): ApmsApiClient {
        return ApmsHttpClient(settings.baseUri(), mapper, meters)
    }

    @Bean
    fun apmsExecutionContextSerializer(): DefaultExecutionContextSerializer {
        return DefaultExecutionContextSerializer()
    }

    @Bean
    @Throws(Exception::class)
    fun apmsJobRepository(
        @Qualifier("writeDataSource") source: HikariDataSource,
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
        @Qualifier("apmsExecutionContextSerializer") apmsExecutionContextSerializer: DefaultExecutionContextSerializer,
        requiredSettings: ApmsScheduleSettings,
    ): JobRepository {
        val factory = JobRepositoryFactoryBean()
        factory.setDataSource(source)
        factory.transactionManager = manager
        factory.setDatabaseType("POSTGRES")
        factory.setTablePrefix("pawbridge_animal.BATCH_")
        factory.setIsolationLevelForCreate("ISOLATION_SERIALIZABLE")
        factory.setSerializer(apmsExecutionContextSerializer)
        factory.afterPropertiesSet()
        return factory.getObject()
    }

    @Bean
    @Throws(Exception::class)
    fun apmsJobExplorer(
        @Qualifier("writeDataSource") source: HikariDataSource,
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
        @Qualifier("apmsExecutionContextSerializer") apmsExecutionContextSerializer: DefaultExecutionContextSerializer,
        requiredSettings: ApmsScheduleSettings,
    ): JobExplorer {
        val factory = JobExplorerFactoryBean()
        factory.setDataSource(source)
        factory.setTransactionManager(manager)
        factory.setTablePrefix("pawbridge_animal.BATCH_")
        factory.setSerializer(apmsExecutionContextSerializer)
        factory.afterPropertiesSet()
        return factory.getObject()
    }

    @Bean
    @Throws(Exception::class)
    fun apmsJobLauncher(@Qualifier("apmsJobRepository") repository: JobRepository): TaskExecutorJobLauncher {
        val launcher = TaskExecutorJobLauncher()
        launcher.setJobRepository(repository)
        launcher.setTaskExecutor(SyncTaskExecutor())
        launcher.afterPropertiesSet()
        return launcher
    }

    @Bean
    fun apmsBatchExecutionRecovery(
        @Qualifier("apmsJobExplorer") explorer: JobExplorer,
        @Qualifier("apmsJobRepository") repository: JobRepository,
        @Qualifier("apmsMetadataClock") clock: Clock,
        properties: ApmsBatchProperties,
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
        settings: ApmsScheduleSettings,
    ): ApmsBatchExecutionRecovery {
        return ApmsBatchExecutionRecovery(explorer, repository, clock, properties, manager, settings.transactionTimeout())
    }

    @Bean
    fun apmsSyncPlanFactory(
        @Qualifier("animalCommandAnimals") animals: AnimalRepository,
        @Qualifier("apmsJobExplorer") explorer: JobExplorer,
        @Qualifier("apmsClock") clock: Clock,
    ): ApmsSyncPlanFactory {
        return ApmsSyncPlanFactory(animals, explorer, clock)
    }

    @Bean
    fun apmsBatchRunner(
        @Qualifier("writeDataSource") source: HikariDataSource,
        recovery: ApmsBatchExecutionRecovery,
        @Qualifier("apmsJobLauncher") launcher: TaskExecutorJobLauncher,
        @Qualifier("apmsAnimalSyncJob") job: Job,
        plans: ApmsSyncPlanFactory,
    ): ApmsBatchRunner {
        return ApmsBatchRunner(source, recovery, launcher, job, plans)
    }

    @Bean
    fun apmsBatchTaskExecutor(settings: ApmsScheduleSettings): ApmsChunkExecutor {
        val executor = ApmsChunkExecutor()
        executor.corePoolSize = settings.workers()
        executor.maxPoolSize = settings.workers()
        executor.queueCapacity = 4
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        executor.setThreadNamePrefix("apms-chunk-")
        executor.setWaitForTasksToCompleteOnShutdown(true)
        return executor
    }

    @Bean
    @DependsOn("apmsApiClient")
    @ConditionalOnProperty(prefix = "apms.batch", name = ["schedule-enabled"], havingValue = "true")
    fun apmsCollectionSchedule(
        runner: ApmsBatchRunner,
        settings: ApmsScheduleSettings,
        @Qualifier("apmsBatchTaskExecutor") requiredExecutor: ApmsChunkExecutor,
    ): ApmsCollectionSchedule {
        return ApmsCollectionSchedule(runner, settings.cron())
    }

    @Bean
    @DependsOn("apmsApiClient")
    @ConditionalOnProperty(prefix = "pawbridge.apms-manual", name = ["enabled"], havingValue = "true")
    fun apmsBatchController(
        runner: ApmsBatchRunner,
        @Qualifier("apmsBatchTaskExecutor") requiredExecutor: ApmsChunkExecutor,
    ): ApmsBatchController {
        return ApmsBatchController(runner)
    }

    /** Either explicit entry point assembles the shared APMS job; neither is on by default. */
    class ApmsEntryPoint : AnyNestedCondition(ConfigurationPhase.PARSE_CONFIGURATION) {
        @ConditionalOnProperty(prefix = "apms.batch", name = ["schedule-enabled"], havingValue = "true")
        class Schedule

        @ConditionalOnProperty(prefix = "pawbridge.apms-manual", name = ["enabled"], havingValue = "true")
        class ManualApi
    }

    /** Wait for submitted chunks without a timeout that would close the writer under live work. */
    class ApmsChunkExecutor : ThreadPoolTaskExecutor() {
        override fun destroy() {
            shutdown()
            var interrupted = false
            try {
                while (!threadPoolExecutor.isTerminated) {
                    try {
                        threadPoolExecutor.awaitTermination(1, TimeUnit.SECONDS)
                    } catch (ignored: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    companion object {
        @Bean
        @JvmStatic
        fun apmsJobScope(): JobScope {
            val scope = JobScope()
            scope.setAutoProxy(false)
            return scope
        }

        @Bean
        @JvmStatic
        fun apmsStepScope(): StepScope {
            val scope = StepScope()
            scope.setAutoProxy(false)
            return scope
        }
    }
}
