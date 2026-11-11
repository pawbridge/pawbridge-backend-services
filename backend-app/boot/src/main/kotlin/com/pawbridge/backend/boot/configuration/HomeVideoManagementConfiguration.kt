package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.homevideo.application.CollectHomeVideoCandidates
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.GeminiCandidateJudge
import com.pawbridge.backend.community.internal.homevideo.infrastructure.HomeVideoCandidateRepository
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoRepository
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JpaHomeVideoCandidateStore
import com.pawbridge.backend.community.internal.homevideo.infrastructure.YouTubeVideoClient
import jakarta.persistence.EntityManagerFactory
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementController
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementExceptionHandler
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pawbridge.home-videos",
    name = ["management-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@Import(
    HomeVideoManagementConfiguration.RefreshConfiguration::class,
    HomeVideoManagementConfiguration.CurationConfiguration::class,
)
class HomeVideoManagementConfiguration {
    @Bean(destroyMethod = "close")
    fun homeVideoHttpClient(): HttpClient {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    @Bean
    fun youTubeVideoClient(
        mapper: ObjectMapper,
        @Value("\${youtube.api-key:\${YOUTUBE_DATA_API_KEY:}}") key: String,
        @Qualifier("homeVideoHttpClient") http: HttpClient,
    ): YouTubeVideoClient {
        return YouTubeVideoClient(mapper, key, http, URI.create("https://www.googleapis.com/youtube/v3"))
    }

    @Bean
    fun homeVideoManagementRepository(@Qualifier("writeJdbc") jdbc: JdbcTemplate): JdbcHomeVideoRepository {
        return JdbcHomeVideoRepository(jdbc)
    }

    @Bean
    fun homeVideoManagement(
        repository: JdbcHomeVideoRepository,
        youtube: YouTubeVideoClient,
        @Qualifier("readClock") clock: Clock,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): HomeVideoManagement {
        return JdbcHomeVideoManagement(repository, youtube, clock, TransactionTemplate(manager))
    }

    @Bean
    fun homeVideoManagementController(management: HomeVideoManagement): HomeVideoManagementController {
        return HomeVideoManagementController(management)
    }

    @Bean
    fun homeVideoManagementExceptionHandler(): HomeVideoManagementExceptionHandler {
        return HomeVideoManagementExceptionHandler()
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "pawbridge.jobs",
        name = ["refresh-enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    @EnableScheduling
    internal class RefreshConfiguration {
        @Bean
        @DependsOn("writeDataSource", "homeVideoHttpClient")
        fun homeVideoRefreshScheduler(): ThreadPoolTaskScheduler {
            val scheduler = ThreadPoolTaskScheduler()
            scheduler.poolSize = 1
            scheduler.setThreadNamePrefix("home-video-refresh-")
            scheduler.setAcceptTasksAfterContextClose(false)
            scheduler.setWaitForTasksToCompleteOnShutdown(false)
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            scheduler.setRemoveOnCancelPolicy(true)
            scheduler.setAwaitTerminationSeconds(5)
            scheduler.setErrorHandler(
                ErrorHandler { failure ->
                    LoggerFactory.getLogger(HomeVideoManagementConfiguration::class.java)
                        .warn("Video refresh scheduling failed ({})", failure.javaClass.simpleName)
                },
            )
            return scheduler
        }

        @Bean
        fun homeVideoRefreshJob(management: HomeVideoManagement): HomeVideoRefreshJob {
            return HomeVideoRefreshJob(management)
        }
    }

    /**
     * 홈 영상 후보 모으기(검색, 규칙, Gemini 판정, 저장). 기본은 꺼짐.
     * 후보 표는 이 기능만 쓰는 JPA 단위다. 쓰기 DataSource를 공유하며 새 풀이나 스키마 생성은 없다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "pawbridge.home-videos",
        name = ["curation-enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    internal class CurationConfiguration {
        @Bean
        fun homeVideoCandidateEntityManagerFactory(
            @Qualifier("writeDataSource") source: DataSource,
        ): LocalContainerEntityManagerFactoryBean {
            val factory = LocalContainerEntityManagerFactoryBean()
            factory.setDataSource(source)
            factory.setPersistenceUnitName("pawbridge-home-video-candidates")
            factory.setManagedTypes(
                PersistenceManagedTypes.of(
                    "com.pawbridge.backend.community.internal.homevideo.infrastructure.HomeVideoCandidate",
                ),
            )
            factory.jpaVendorAdapter = HibernateJpaVendorAdapter()
            factory.setJpaPropertyMap(
                mapOf(
                    "hibernate.dialect" to "org.hibernate.dialect.PostgreSQLDialect",
                    "hibernate.boot.allow_jdbc_metadata_access" to false,
                    "hibernate.hbm2ddl.auto" to "none",
                    "jakarta.persistence.schema-generation.database.action" to "none",
                    "hibernate.default_schema" to "pawbridge_community",
                    "hibernate.jdbc.time_zone" to "UTC",
                    "hibernate.physical_naming_strategy" to
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                ),
            )
            return factory
        }

        /**
         * 같은 쓰기 DataSource를 지정해, 다음 단계에서 기존 JDBC 홈 영상 코드도 이 트랜잭션에 함께 묶을 수 있게 한다.
         * 한 작업에 JDBC와 JPA 트랜잭션 관리자를 섞으면 커밋 시점이 갈린다(README의 APMS 기록).
         */
        @Bean
        fun homeVideoCandidateTransactionManager(
            @Qualifier("homeVideoCandidateEntityManagerFactory") factory: EntityManagerFactory,
            @Qualifier("writeDataSource") source: DataSource,
        ): JpaTransactionManager {
            val manager = JpaTransactionManager(factory)
            manager.dataSource = source
            manager.defaultTimeout = 10
            manager.isValidateExistingTransaction = true
            return manager
        }

        @Bean
        fun homeVideoCandidateRepository(
            @Qualifier("homeVideoCandidateEntityManagerFactory") factory: EntityManagerFactory,
        ): HomeVideoCandidateRepository {
            val entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory)
            return JpaRepositoryFactory(entityManager).getRepository(HomeVideoCandidateRepository::class.java)
        }

        @Bean
        fun homeVideoCandidateStore(
            repository: HomeVideoCandidateRepository,
            @Qualifier("homeVideoCandidateTransactionManager") manager: JpaTransactionManager,
        ): JpaHomeVideoCandidateStore {
            return JpaHomeVideoCandidateStore(repository, TransactionTemplate(manager))
        }

        @Bean
        fun homeVideoCandidateJudge(
            mapper: ObjectMapper,
            @Value("\${gemini.api-key:\${GEMINI_API_KEY:}}") key: String,
            @Value("\${pawbridge.home-videos.gemini-model:gemini-3.5-flash-lite}") model: String,
            @Qualifier("homeVideoHttpClient") http: HttpClient,
        ): GeminiCandidateJudge {
            val endpoint = URI.create("https://generativelanguage.googleapis.com/v1beta/models")
            return GeminiCandidateJudge(mapper, key, model, http, endpoint)
        }

        @Bean
        fun collectHomeVideoCandidates(
            youtube: YouTubeVideoClient,
            judge: GeminiCandidateJudge,
            store: JpaHomeVideoCandidateStore,
            repository: JdbcHomeVideoRepository,
            @Qualifier("readClock") clock: Clock,
        ): CollectHomeVideoCandidates {
            return CollectHomeVideoCandidates(youtube, judge, store, repository, clock)
        }
    }

    class HomeVideoRefreshJob(private val management: HomeVideoManagement) {
        @Scheduled(scheduler = "homeVideoRefreshScheduler", fixedDelay = 3_600_000, initialDelay = 60_000)
        fun refresh() {
            try {
                management.refresh()
            } catch (failure: RuntimeException) {
                LoggerFactory.getLogger(HomeVideoRefreshJob::class.java)
                    .warn(
                        "YouTube metadata refresh unavailable; retry on next schedule ({})",
                        failure.javaClass.simpleName,
                    )
            }
        }
    }
}
