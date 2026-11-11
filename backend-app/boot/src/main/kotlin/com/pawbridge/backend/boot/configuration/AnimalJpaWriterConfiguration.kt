package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.user.internal.animalcommand.infrastructure.JdbcAnimalCommandActors
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter

/** Shared, explicitly imported Animal writer; no component scan, new pool or schema generation. */
@Configuration(proxyBeanMethods = false)
@Import(AnimalSearchConfiguration::class, JpaAuditingConfiguration::class)
class AnimalJpaWriterConfiguration {
    @Bean
    fun animalCommandEntityManagerFactory(
        @Qualifier("writeDataSource") source: DataSource,
        @Value("\${pawbridge.animal-chatbot.enabled:false}") chatbotFlag: String,
    ): LocalContainerEntityManagerFactoryBean {
        val factory = LocalContainerEntityManagerFactoryBean()
        factory.setDataSource(source)
        factory.setPersistenceUnitName("pawbridge-animal-command-writer")
        val managed = ArrayList<String>(listOf(MODEL + "Animal", MODEL + "Shelter", MODEL + "ShelterPublicInformation", MODEL + "BaseTimeEntity"))
        if ("true".equals(chatbotFlag, ignoreCase = true)) {
            managed.add("com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotSession")
            managed.add("com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotMessage")
            managed.add("com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotBlockLog")
        }
        factory.setManagedTypes(PersistenceManagedTypes.of(*managed.toTypedArray()))
        factory.jpaVendorAdapter = HibernateJpaVendorAdapter()
        factory.setJpaPropertyMap(
            mapOf(
                "hibernate.dialect" to "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.boot.allow_jdbc_metadata_access" to false,
                "hibernate.hbm2ddl.auto" to "none",
                "jakarta.persistence.schema-generation.database.action" to "none",
                "hibernate.default_schema" to "pawbridge_animal",
                "hibernate.jdbc.time_zone" to "UTC",
                "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            ),
        )
        return factory
    }

    @Bean
    fun animalCommandJpaTransactionManager(
        @Qualifier("animalCommandEntityManagerFactory") factory: EntityManagerFactory,
        @Qualifier("writeDataSource") source: DataSource,
    ): JpaTransactionManager {
        val manager = JpaTransactionManager(factory)
        manager.dataSource = source
        manager.defaultTimeout = 15
        manager.isValidateExistingTransaction = true
        return manager
    }

    @Bean
    fun animalCommandEntityManager(
        @Qualifier("animalCommandEntityManagerFactory") factory: EntityManagerFactory,
    ): EntityManager {
        return SharedEntityManagerCreator.createSharedEntityManager(factory)
    }

    @Bean
    fun animalCommandRepositoryFactory(
        @Qualifier("animalCommandEntityManager") entityManager: EntityManager,
    ): JpaRepositoryFactory {
        return JpaRepositoryFactory(entityManager)
    }

    @Bean
    fun animalCommandAnimals(
        @Qualifier("animalCommandRepositoryFactory") repositories: JpaRepositoryFactory,
    ): AnimalRepository {
        return repositories.getRepository(AnimalRepository::class.java)
    }

    @Bean
    fun animalCommandShelters(
        @Qualifier("animalCommandRepositoryFactory") repositories: JpaRepositoryFactory,
    ): ShelterRepository {
        return repositories.getRepository(ShelterRepository::class.java)
    }

    @Bean
    fun animalCommandActors(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeDataSource") source: DataSource,
    ): AnimalCommandActors {
        if (jdbc.dataSource !== source) {
            throw IllegalArgumentException("Animal actors must use the same writer DataSource")
        }
        return JdbcAnimalCommandActors(jdbc)
    }

    @Bean
    fun animalCommandSearch(
        @Qualifier("writeDataSource") source: DataSource,
        analyzer: KoreanSearchAnalyzer,
    ): SearchDocumentWriter {
        return SearchDocumentWriter(source, analyzer)
    }

    private companion object {
        const val MODEL = "com.pawbridge.backend.animal.internal.command.infrastructure.entity."
    }
}
