package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure.ShelterDirectoryCollector
import com.pawbridge.backend.boot.configuration.NoteExpiryConfiguration
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import com.pawbridge.backend.boot.configuration.VisitReadConfiguration
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.infrastructure.YouTubeVideoClient
import com.pawbridge.backend.community.internal.note.application.NoteBlockCreation
import com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval
import com.pawbridge.backend.community.internal.note.application.NoteExpiry
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockCreation
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockRemoval
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchRebuild
import com.pawbridge.backend.community.internal.report.application.AnimalReports
import com.pawbridge.backend.community.internal.report.web.AnimalReportController
import com.pawbridge.backend.community.internal.report.web.AnimalReportExceptionHandler
import com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits
import com.pawbridge.backend.community.internal.visit.application.VisitCollection
import com.pawbridge.backend.community.internal.visit.application.VisitCountReader
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity
import com.pawbridge.backend.community.internal.visit.web.VisitTodayController
import com.pawbridge.backend.community.internal.visit.web.VisitTodayExceptionHandler
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler
import com.pawbridge.backend.store.internal.directorder.infrastructure.JdbcProductSearchRebuild
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import com.zaxxer.hikari.HikariDataSource
import io.mockk.confirmVerified
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import jakarta.servlet.MultipartConfigElement
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.unit.DataSize
import java.net.http.HttpClient
import java.time.Clock
import java.time.ZoneOffset
import java.util.function.Function
import javax.sql.DataSource

class PawBridgeApplicationTest {
    @Test
    fun `설정이 없을 때__시작을 검증하면__기본 데이터베이스를 고르지 않는다`() {
        // given
        val environment = emptyMap<String, String>()

        // when
        val thrown = catchThrowable { ReadDatabaseSettings.fromEnvironment(Function { key -> environment[key] }) }

        // then
        assertThat(thrown)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PAWBRIDGE_DB_URL")
    }

    @Test
    fun `명시적 입력이 주어졌을 때__시작을 검증하면__값을 보존하고 풀 크기를 제한한다`() {
        // given
        val inputs: Map<String, String> = inputs()

        // when
        val settings = ReadDatabaseSettings.fromEnvironment(Function { key -> inputs[key] })

        // then
        assertThat(settings.jdbcUrl()).isEqualTo(inputs["PAWBRIDGE_DB_URL"])
        assertThat(settings.username()).isEqualTo(inputs["PAWBRIDGE_DB_USER"])
        assertThat(settings.maximumPoolSize()).isEqualTo(10)
        assertThat(settings.toString()).doesNotContain(inputs["PAWBRIDGE_DB_PASSWORD"])
    }

    @Test
    fun `잘못된 엔드포인트나 풀이 주어졌을 때__시작을 검증하면__입력을 되풀이하지 않고 거부한다`() {
        // given
        val urls = arrayOf(
            "jdbc:h2:mem:ignored",
            "jdbc:postgresql://localhost/pawbridge_f2_ci?password=private-value",
            "jdbc:postgresql://private-value@localhost/pawbridge_f2_ci",
            "jdbc:postgresql://localhost:0/pawbridge_f2_ci",
        )

        // when
        val rejectedUrls = urls.map { url ->
            val values = inputs()
            values["PAWBRIDGE_DB_URL"] = url
            catchThrowable { ReadDatabaseSettings.fromEnvironment(Function { key -> values[key] }) }
        }
        val rejectedSizes = arrayOf("0", "51", "invalid").map { size ->
            val values = inputs()
            values["PAWBRIDGE_DB_POOL_MAX_SIZE"] = size
            catchThrowable { ReadDatabaseSettings.fromEnvironment(Function { key -> values[key] }) }
        }

        // then
        assertThat(rejectedUrls).hasSize(urls.size).allSatisfy { thrown ->
            assertThat(thrown).hasMessage("Missing or invalid explicit setting: PAWBRIDGE_DB_URL")
        }
        assertThat(rejectedSizes).hasSize(3).allSatisfy { thrown ->
            assertThat(thrown).hasMessage("Missing or invalid explicit setting: PAWBRIDGE_DB_POOL_MAX_SIZE")
        }
    }

    @Test
    fun `리포트가 명시적으로 켜졌을 때__컨텍스트가 시작되면__연결 풀과 트랜잭션 관리자를 늘리지 않는다`() {
        // given
        val values = F3TestInputs.values()
        val settings = ReadDatabaseSettings.fromEnvironment(values::get)
        val writeSettings = PostWriteSettings.fromEnvironment(values::get)

        // when
        WebApplicationContextRunner().withUserConfiguration(PawBridgeApplication::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readDatabaseSettings", settings)
                context.beanFactory.registerSingleton("postWriteSettings", writeSettings)
            }
            .withPropertyValues("spring.sql.init.mode=never", "spring.jmx.enabled=false", "pawbridge.animal-reports.enabled=true")
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(AnimalReports::class.java)
                assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).hasSize(2)
            }
    }

    @Test
    fun `명시적 후보 조립이 주어졌을 때__컨텍스트가 시작되면__명시한 읽기와 쓰기 의존성만 조립한다`() {
        // given
        val values = F3TestInputs.values()
        val settings = ReadDatabaseSettings.fromEnvironment(values::get)
        val writeSettings = PostWriteSettings.fromEnvironment(values::get)

        // when
        WebApplicationContextRunner()
            .withUserConfiguration(PawBridgeApplication::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readDatabaseSettings", settings)
                context.beanFactory.registerSingleton("postWriteSettings", writeSettings)
            }
            .withPropertyValues("spring.sql.init.mode=never", "spring.jmx.enabled=false", "pawbridge.visits.enabled=true")
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(JdbcPostSearchRebuild::class.java)).isEmpty()
                assertThat(context.getBeansOfType(JdbcProductSearchRebuild::class.java)).isEmpty()
                assertThat(context.getBeansOfType(MockOrderPaymentController::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ShelterDirectoryCollector::class.java)).isEmpty()
                assertThat(context.getBeansOfType(StoreMyPage::class.java)).isEmpty()
                assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).isEmpty()
                assertThat(context.getBeansOfType(JpaRepository::class.java)).isEmpty()
                assertThat(context.getBeansOfType(StoreCatalog::class.java)).isEmpty()
                assertThat(context.getBeansOfType(StoreCatalogController::class.java)).isEmpty()
                assertThat(context.getBeansOfType(StoreCatalogExceptionHandler::class.java)).isEmpty()
                assertThat(context).hasSingleBean(ReadTodayVisits::class.java)
                assertThat(context.getBeansOfType(AnimalReports::class.java)).isEmpty()
                assertThat(context.getBeansOfType(AnimalReportController::class.java)).isEmpty()
                assertThat(context.getBeansOfType(AnimalReportExceptionHandler::class.java)).isEmpty()
                assertThat(context.getBeansOfType(UserAnimalActivities::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ShelterMembership::class.java)).isEmpty()
                assertThat(context.getBeansOfType(AuthSessions::class.java)).isEmpty()
                assertThat(context.getBeansOfType(JwtSessionTokens::class.java)).isEmpty()
                val periodTransaction = context.getBean("adminPostStatisticsTransaction", TransactionTemplate::class.java)
                assertThat(periodTransaction.isReadOnly).isTrue()
                assertThat(periodTransaction.timeout).isEqualTo(3)
                assertThat(periodTransaction.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                assertThat(context.getBean("readOnlyTransaction", TransactionTemplate::class.java).isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).hasSize(2)
                assertThat(context.getBeansOfType(HomeVideoManagement::class.java)).isEmpty()
                assertThat(context.getBeansOfType(YouTubeVideoClient::class.java)).isEmpty()
                assertThat(context.getBeansOfType(HttpClient::class.java)).isEmpty()
                assertThat(context.getBeansOfType(TaskScheduler::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ScheduledTaskHolder::class.java)).isEmpty()
                assertThat(context.getBeansOfType(VisitCollection::class.java)).isEmpty()
                assertThat(context.getBeansOfType(VisitIdentity::class.java)).isEmpty()
                assertThat(context.getBeansOfType(Clock::class.java)).containsOnlyKeys("readClock")
                assertThat(context.getBean("readClock", Clock::class.java).zone).isEqualTo(ZoneOffset.UTC)
                val pools = context.getBeansOfType(HikariDataSource::class.java)
                assertThat(pools).containsOnlyKeys("dataSource")
                val pool = pools["dataSource"]!!
                assertThat(pool.poolName).isEqualTo("pawbridge-db")
                assertThat(pool.isReadOnly).isFalse()
                assertThat(pool.maximumPoolSize).isEqualTo(10)
                assertThat(pool.minimumIdle).isEqualTo(2)
                assertThat(pool.connectionTimeout).isEqualTo(5000)
                assertThat(pool.validationTimeout).isEqualTo(1000)
                assertThat(pool.initializationFailTimeout).isEqualTo(-1)
                val readJdbc = context.getBean("readJdbc", JdbcTemplate::class.java)
                val searchJdbc = context.getBean("searchJdbc", JdbcTemplate::class.java)
                assertThat(readJdbc.queryTimeout).isEqualTo(3)
                assertThat(searchJdbc.queryTimeout).isEqualTo(5)
                val manager = context.getBean("transactionManager", JdbcTransactionManager::class.java)
                assertThat(manager.isEnforceReadOnly).isTrue()
                val readTransaction = context.getBean("readOnlyTransaction", TransactionTemplate::class.java)
                val searchTransaction = context.getBean("searchReadOnlyTransaction", TransactionTemplate::class.java)
                assertThat(readTransaction.timeout).isEqualTo(3)
                assertThat(searchTransaction.timeout).isEqualTo(5)
                assertThat(readTransaction.isReadOnly).isTrue()
                assertThat(searchTransaction.isReadOnly).isTrue()
                val writeJdbc = context.getBean("writeJdbc", JdbcTemplate::class.java)
                val sharedWriteTransaction = context.getBean("writeTransaction", TransactionTemplate::class.java)
                assertThat(writeJdbc.queryTimeout).isEqualTo(5)
                assertThat(sharedWriteTransaction.timeout).isEqualTo(10)
                val blockRemoval = context.getBean(NoteBlockRemoval::class.java)
                assertThat(blockRemoval).isInstanceOf(JdbcNoteBlockRemoval::class.java)
                val blockCreation = context.getBean(NoteBlockCreation::class.java)
                assertThat(blockCreation).isInstanceOf(JdbcNoteBlockCreation::class.java)
                val streams = context.getBean(PrivateNoteStreamRegistry::class.java)
                assertThat(streams.isRunning()).isTrue()
                assertThat(streams.phase).isEqualTo(Int.MAX_VALUE)
                assertThat(context.getBeansOfType(NoteExpiry::class.java)).isEmpty()
                assertThat(context.getBeansOfType(NoteExpiryConfiguration.NoteExpiryJob::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
                assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
                assertThat(pool.hikariPoolMXBean.activeConnections).isZero()
            }
    }

    @Test
    fun `후보 멀티파트 설정이 주어졌을 때__서블릿 설정에 바인딩하면__파일과 요청 한도를 보존한다`() {
        // given
        // when
        WebApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration::class.java))
            .withPropertyValues("spring.config.location=classpath:/application.properties")
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                val properties = context.getBean(MultipartProperties::class.java)
                assertThat(properties.maxFileSize).isEqualTo(DataSize.ofMegabytes(100))
                assertThat(properties.maxRequestSize).isEqualTo(DataSize.ofMegabytes(200))
                val servletConfig = context.getBean(MultipartConfigElement::class.java)
                assertThat(servletConfig.maxFileSize).isEqualTo(DataSize.ofMegabytes(100).toBytes())
                assertThat(servletConfig.maxRequestSize).isEqualTo(DataSize.ofMegabytes(200).toBytes())
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["false", "absent"])
    fun `방문 플래그가 false이거나 없을 때__실제 설정을 조립하면__방문 빈과 JDBC 호출이 없다`(
        flag: String,
    ) {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val transaction = mockk<TransactionTemplate>()
        var runner = WebApplicationContextRunner()
            .withUserConfiguration(VisitReadConfiguration::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readJdbc", jdbc)
                context.beanFactory.registerSingleton("readOnlyTransaction", transaction)
                context.beanFactory.registerSingleton("readClock", Clock.systemUTC())
            }
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.visits.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(VisitCountReader::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ReadTodayVisits::class.java)).isEmpty()
            assertThat(context.getBeansOfType(VisitTodayController::class.java)).isEmpty()
            assertThat(context.getBeansOfType(VisitTodayExceptionHandler::class.java)).isEmpty()
            confirmVerified(jdbc, transaction)
        }
    }

    private fun inputs(): MutableMap<String, String> {
        return HashMap(
            mapOf(
                "PAWBRIDGE_DB_URL" to "jdbc:postgresql://127.0.0.1:5432/pawbridge_f2_ci",
                "PAWBRIDGE_DB_USER" to "fixture-reader",
                "PAWBRIDGE_DB_PASSWORD" to "synthetic-only",
            ),
        )
    }
}
