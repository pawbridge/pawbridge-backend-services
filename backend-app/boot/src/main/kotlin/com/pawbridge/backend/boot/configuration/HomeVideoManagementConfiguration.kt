package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.homevideo.application.CollectHomeVideoCandidates
import com.pawbridge.backend.community.internal.homevideo.application.CurateHomeVideos
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoCurationException
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.GeminiCandidateJudge
import com.pawbridge.backend.community.internal.homevideo.infrastructure.HomeVideoBoardRepository
import com.pawbridge.backend.community.internal.homevideo.infrastructure.HomeVideoCandidateRepository
import com.pawbridge.backend.community.internal.homevideo.infrastructure.HomeVideoCurationRepository
import com.pawbridge.backend.community.internal.homevideo.infrastructure.HomeVideoEntityRepository
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoRepository
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JpaCurationBoard
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
import org.springframework.beans.factory.ObjectProvider
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
        fun homeVideoRefreshJob(
            management: HomeVideoManagement,
            curation: ObjectProvider<CurateHomeVideos>,
        ): HomeVideoRefreshJob {
            return HomeVideoRefreshJob(management, curation.ifAvailable)
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
                    HOME_VIDEO_MODEL + "HomeVideoCandidate",
                    HOME_VIDEO_MODEL + "HomeVideoEntity",
                    HOME_VIDEO_MODEL + "HomeVideoBoardEntity",
                    HOME_VIDEO_MODEL + "HomeVideoCurationEntity",
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
         * 자동 교체는 후보 표, 홈 영상 표, 목록 잠금, 실행 기록을 모두 이 JPA 단위로 쓰므로 한 작업에 관리자가 하나다.
         * 한 작업에 JDBC와 JPA 트랜잭션 관리자를 섞으면 커밋 시점이 갈린다(README의 APMS 기록).
         * dataSource를 지정해 두면 같은 트랜잭션 안의 JDBC 접근도 같은 연결을 쓴다(animal 단위와 같은 설정).
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
        fun homeVideoCurationRepositories(
            @Qualifier("homeVideoCandidateEntityManagerFactory") factory: EntityManagerFactory,
        ): JpaRepositoryFactory {
            val entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory)
            return JpaRepositoryFactory(entityManager)
        }

        @Bean
        fun homeVideoCandidateRepository(
            @Qualifier("homeVideoCurationRepositories") repositories: JpaRepositoryFactory,
        ): HomeVideoCandidateRepository {
            return repositories.getRepository(HomeVideoCandidateRepository::class.java)
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

        @Bean
        fun homeVideoCurationBoard(
            @Qualifier("homeVideoCurationRepositories") repositories: JpaRepositoryFactory,
            candidates: HomeVideoCandidateRepository,
            @Qualifier("homeVideoCandidateTransactionManager") manager: JpaTransactionManager,
        ): JpaCurationBoard {
            return JpaCurationBoard(
                repositories.getRepository(HomeVideoEntityRepository::class.java),
                repositories.getRepository(HomeVideoBoardRepository::class.java),
                candidates,
                repositories.getRepository(HomeVideoCurationRepository::class.java),
                TransactionTemplate(manager),
            )
        }

        @Bean
        fun curateHomeVideos(
            collect: CollectHomeVideoCandidates,
            board: JpaCurationBoard,
            youtube: YouTubeVideoClient,
            @Qualifier("readClock") clock: Clock,
        ): CurateHomeVideos {
            return CurateHomeVideos(collect, board, youtube, clock)
        }
    }

    /**
     * 1시간마다: 상태 갱신 → (자동 교체가 켜져 있으면) 빈칸 채우기 → 사용 기록 재확인 → 주간 교체.
     * 갱신이 실패하면 영상 상태를 믿을 수 없어 자동 교체도 다음 시간으로 미룬다. 단계마다 실패를 따로 받아 다음 단계를 막지 않는다.
     */
    class HomeVideoRefreshJob(
        private val management: HomeVideoManagement,
        private val curation: CurateHomeVideos? = null,
    ) {
        @Scheduled(scheduler = "homeVideoRefreshScheduler", fixedDelay = 3_600_000, initialDelay = 60_000)
        fun refresh() {
            try {
                management.refresh()
            } catch (failure: RuntimeException) {
                log.warn("YouTube metadata refresh unavailable; retry on next schedule ({})", failure.javaClass.simpleName)
                return
            }
            if (curation == null) {
                return
            }
            step("fill gaps") {
                val filled = curation.fillGaps()
                if (filled > 0) {
                    log.info("Home video gaps filled: {}", filled)
                }
            }
            step("recheck used") {
                curation.recheckUsed()
            }
            step("weekly curation") {
                if (curation.weekly()) {
                    log.info("Home video weekly curation finished")
                }
            }
        }

        private fun step(name: String, action: () -> Unit) {
            try {
                action()
            } catch (failure: HomeVideoCurationException) {
                // 이 예외의 메시지에는 비밀값이 없다(작성 규칙).
                log.warn("Home video {} skipped: {}", name, failure.message)
            } catch (failure: RuntimeException) {
                // 다른 예외는 주소나 키를 담을 수 있어 종류만 남긴다.
                log.warn("Home video {} failed ({})", name, failure.javaClass.simpleName)
            }
        }

        private companion object {
            private val log = LoggerFactory.getLogger(HomeVideoRefreshJob::class.java)
        }
    }

    private companion object {
        private const val HOME_VIDEO_MODEL = "com.pawbridge.backend.community.internal.homevideo.infrastructure."
    }
}
