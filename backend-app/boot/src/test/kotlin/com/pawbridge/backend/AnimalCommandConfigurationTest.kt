package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.BaseTimeEntity
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.ShelterPublicInformation
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandController
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandExceptionHandler
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.boot.configuration.AnimalCommandConfiguration
import com.pawbridge.backend.boot.configuration.AnimalDiscoveryConfiguration
import com.pawbridge.backend.boot.configuration.AnimalRecommendationsConfiguration
import com.pawbridge.backend.boot.configuration.AnimalSearchConfiguration
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.PlatformTransactionManager
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalCommandConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun `명시적 true가 아닌 명령 플래그__DB 입력 없이 조립하면__명령 검색 감사 빈을 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.animal-commands.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(JpaTransactionManager::class.java)
                .doesNotHaveBean(AnimalCommandService::class.java)
                .doesNotHaveBean(AnimalCommandActors::class.java)
                .doesNotHaveBean(AnimalCommandController::class.java)
                .doesNotHaveBean(AnimalCommandExceptionHandler::class.java)
                .doesNotHaveBean(KoreanSearchAnalyzer::class.java)
                .doesNotHaveBean("jpaAuditingHandler")
        }
    }

    @Test
    fun `명령 활성화__조립하면__DB 접근 없이 쓰기 단위 하나와 선택된 저장소를 만든다`() {
        // given
        val writer = mockk<DataSource>()
        val reader = mockk<DataSource>()
        excludeRecords {
            writer.hashCode()
            writer.toString()
            writer.equals(any())
            reader.hashCode()
            reader.toString()
            reader.equals(any())
        }
        val owned = AtomicReference<KoreanSearchAnalyzer>()
        val unit = AtomicReference<EntityManagerFactory>()
        val runner = writerInputs(writer)
            .withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withPropertyValues("pawbridge.animal-commands.enabled=TRUE")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            val factory = context.getBean(
                "&animalCommandEntityManagerFactory",
                LocalContainerEntityManagerFactoryBean::class.java,
            )
            val emf = context.getBean("animalCommandEntityManagerFactory", EntityManagerFactory::class.java)
            unit.set(emf)
            assertThat(factory.persistenceUnitInfo!!.managedClassNames).containsExactlyInAnyOrder(
                Animal::class.java.name,
                Shelter::class.java.name,
                ShelterPublicInformation::class.java.name,
                BaseTimeEntity::class.java.name,
            )
            assertThat(factory.jpaPropertyMap)
                .containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                .containsEntry("hibernate.hbm2ddl.auto", "none")
                .containsEntry("jakarta.persistence.schema-generation.database.action", "none")
                .containsEntry("hibernate.default_schema", "pawbridge_animal")
                .containsEntry("hibernate.jdbc.time_zone", "UTC")
                .containsEntry(
                    "hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                )
            assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(AnimalRepository::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(ShelterRepository::class.java)).hasSize(1)
            val manager = context.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
            assertThat(manager.defaultTimeout).isEqualTo(15)
            assertThat(context).hasSingleBean(AnimalCommandService::class.java)
            owned.set(context.getBean(KoreanSearchAnalyzer::class.java))
            assertThat(context)
                .hasSingleBean(AnimalCommandController::class.java)
                .hasSingleBean(AnimalCommandExceptionHandler::class.java)
                .doesNotHaveBean(AnimalDiscovery::class.java)
                .doesNotHaveBean(AnimalDiscoveryController::class.java)
                .doesNotHaveBean(AnimalRecommendations::class.java)
                .hasBean("jpaAuditingHandler")
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer::class.java)).hasSize(1)
            confirmVerified(writer, reader)
        }

        // then
        assertThat(unit.get().isOpen).isFalse()
        assertThatThrownBy { owned.get().tokens("말티즈") }.isInstanceOf(IllegalStateException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["discovery", "recommendations", "both"])
    fun `명령과 조회 활성화__조립하면__분석기를 하나만 만들고 닫으며 읽기 자원을 건드리지 않는다`(
        mode: String,
    ) {
        // given
        val writer = mockk<DataSource>()
        val reader = mockk<DataSource>()
        val readerManager = mockk<PlatformTransactionManager>()
        excludeRecords {
            writer.hashCode()
            writer.toString()
            writer.equals(any())
            reader.hashCode()
            reader.toString()
            reader.equals(any())
            readerManager.hashCode()
            readerManager.toString()
            readerManager.equals(any())
        }
        val owned = AtomicReference<KoreanSearchAnalyzer>()

        var runner = writerInputs(writer)
            .withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withBean("transactionManager", PlatformTransactionManager::class.java, Supplier { readerManager })
            .withPropertyValues(
                "pawbridge.animal-commands.enabled=true",
                "python-ai-service.url=https://fixture.example.invalid",
            )
        if (mode != "recommendations") {
            runner = runner.withPropertyValues("pawbridge.animal-discovery.enabled=true")
        }
        if (mode != "discovery") {
            runner = runner.withPropertyValues("pawbridge.animal-recommendations.enabled=true")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer::class.java)).hasSize(1)
            owned.set(context.getBean(KoreanSearchAnalyzer::class.java))
            assertThat(context.containsBean("animalDiscoveryController")).isEqualTo(mode != "recommendations")
            confirmVerified(writer, reader, readerManager)
        }
        assertThatThrownBy { owned.get().tokens("말티즈") }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `다른 소스를 쓰는 행위자 JDBC__명령을 활성화하면__DB 연결을 열기 전에 실패한다`() {
        // given
        val writer = mockk<DataSource>()
        val other = mockk<DataSource>()
        excludeRecords {
            writer.hashCode()
            writer.toString()
            writer.equals(any())
            other.hashCode()
            other.toString()
            other.equals(any())
        }
        val runner = runner()
            .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(other) })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.animal-commands.enabled=true")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage("Animal actors must use the same writer DataSource")
            confirmVerified(writer, other)
        }
    }

    @Test
    fun `읽기 입력만 있음__명령을 활성화하면__읽기 소스를 쓰지 않고 실패한다`() {
        // given
        val reader = mockk<DataSource>()
        excludeRecords {
            reader.hashCode()
            reader.toString()
            reader.equals(any())
        }
        val runner = runner()
            .withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.animal-commands.enabled=true")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            confirmVerified(reader)
        }
    }

    companion object {
        private fun runner(): ApplicationContextRunner {
            return ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
                .withUserConfiguration(
                    AnimalCommandConfiguration::class.java,
                    AnimalDiscoveryConfiguration::class.java,
                    AnimalRecommendationsConfiguration::class.java,
                    AnimalSearchConfiguration::class.java,
                )
        }

        private fun writerInputs(writer: DataSource): ApplicationContextRunner {
            return runner()
                .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
                .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
        }
    }
}
