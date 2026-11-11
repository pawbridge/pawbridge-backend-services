package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.FeignPythonChatbotClient
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.PythonChatbotClient
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotBlockLogRepository
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotMessageRepository
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotSessionRepository
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotClientIpResolver
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotCookieService
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotGuardService
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotIpHashService
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotRateLimitService
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotController
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotExceptionHandler
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterPublicInformationRepository
import com.pawbridge.backend.boot.javaIsBlank
import feign.Client
import feign.Request
import java.net.URI
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Opt-in only. Reuses the existing Animal writer; no pool, schema generation, or component scan. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "pawbridge.animal-chatbot", name = ["enabled"], havingValue = "true", matchIfMissing = false)
@ConditionalOnExpression("'postgresql'.equals('\${pawbridge.animal-query.backend:}')")
@Import(AnimalJpaWriterConfiguration::class)
class AnimalChatbotConfiguration {
    @Bean
    fun animalChatbotSettings(environment: Environment): Settings {
        try {
            val url = required(environment, "python-ai-service.url")
            val endpoint = URI.create(url)
            if (!("http".equals(endpoint.scheme, ignoreCase = true) || "https".equals(endpoint.scheme, ignoreCase = true)) ||
                endpoint.host == null
            ) {
                throw IllegalArgumentException()
            }
            val host = required(environment, "spring.data.redis.host")
            val port = environment.getProperty("spring.data.redis.port", Int::class.javaObjectType, 6379)
            val database = environment.getProperty("spring.data.redis.database", Int::class.javaObjectType, 0)
            if (port < 1 || port > 65535 || database < 0) {
                throw IllegalArgumentException()
            }
            val redis = URI("redis", null, host, port, null, null, null)
            if (redis.host == null) {
                throw IllegalArgumentException()
            }
            return Settings(
                url,
                required(environment, "python-ai-service.internal-api-key"),
                required(environment, "chatbot.security.ip-hash-secret"),
                redis.toString(),
                database,
                environment.getProperty("spring.data.redis.password", ""),
                environment.getProperty("chatbot.rate-limit.anonymous.ten-minutes", Long::class.javaObjectType, 5L),
                environment.getProperty("chatbot.rate-limit.anonymous.daily", Long::class.javaObjectType, 20L),
                environment.getProperty("chatbot.rate-limit.ip.ten-minutes", Long::class.javaObjectType, 20L),
                environment.getProperty("chatbot.rate-limit.ip.daily", Long::class.javaObjectType, 100L),
                environment.getProperty("chatbot.rate-limit.global.daily", Long::class.javaObjectType, 300L),
            )
        } catch (invalid: Exception) {
            throw IllegalArgumentException(INVALID)
        }
    }

    private fun required(environment: Environment, name: String): String {
        val value = environment.getProperty(name)
        if (value == null || javaIsBlank(value)) {
            throw IllegalArgumentException()
        }
        return value
    }

    fun interface RedisClientFactory {
        fun create(config: Config): RedissonClient
    }

    @Bean
    @ConditionalOnMissingBean(name = ["animalChatbotRedisClientFactory"])
    fun animalChatbotRedisClientFactory(): RedisClientFactory {
        return RedisClientFactory { config -> Redisson.create(config) }
    }

    @Bean(destroyMethod = "shutdown")
    fun animalChatbotRedis(
        settings: Settings,
        @Qualifier("animalChatbotRedisClientFactory") factory: RedisClientFactory,
    ): RedissonClient {
        val config = Config()
        val server = config.useSingleServer()
            .setAddress(settings.redisAddress)
            .setDatabase(settings.redisDatabase)
        if (!javaIsBlank(settings.redisPassword)) {
            server.setPassword(settings.redisPassword)
        }
        return factory.create(config)
    }

    @Bean
    fun animalChatbotSessions(
        @Qualifier("animalCommandRepositoryFactory") repositories: JpaRepositoryFactory,
    ): ChatbotSessionRepository {
        return repositories.getRepository(ChatbotSessionRepository::class.java)
    }

    @Bean
    fun animalChatbotMessages(
        @Qualifier("animalCommandRepositoryFactory") repositories: JpaRepositoryFactory,
    ): ChatbotMessageRepository {
        return repositories.getRepository(ChatbotMessageRepository::class.java)
    }

    @Bean
    fun animalChatbotBlocks(
        @Qualifier("animalCommandRepositoryFactory") repositories: JpaRepositoryFactory,
    ): ChatbotBlockLogRepository {
        return repositories.getRepository(ChatbotBlockLogRepository::class.java)
    }

    @Bean
    fun animalChatbotReads(
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
    ): TransactionTemplate {
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true
        reads.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        return reads
    }

    @Bean
    fun animalChatbotWrites(
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
    ): TransactionTemplate {
        val writes = TransactionTemplate(manager)
        writes.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        return writes
    }

    @Bean
    fun animalChatbotCookies(): ChatbotCookieService {
        return ChatbotCookieService()
    }

    @Bean
    fun animalChatbotClientIp(): ChatbotClientIpResolver {
        return ChatbotClientIpResolver()
    }

    @Bean
    fun animalChatbotGuard(): ChatbotGuardService {
        return ChatbotGuardService()
    }

    @Bean
    fun animalChatbotIpHash(settings: Settings): ChatbotIpHashService {
        return ChatbotIpHashService(settings.ipHashSecret)
    }

    @Bean
    @ConditionalOnMissingBean(ChatbotRateLimitService::class)
    fun animalChatbotRateLimits(
        @Qualifier("animalChatbotRedis") client: RedissonClient,
        settings: Settings,
    ): ChatbotRateLimitService {
        return ChatbotRateLimitService(
            client,
            settings.anonymousTenMinutes,
            settings.anonymousDaily,
            settings.ipTenMinutes,
            settings.ipDaily,
            settings.globalDaily,
        )
    }

    @Bean
    fun animalChatbotPython(settings: Settings, mapper: ObjectMapper): PythonChatbotClient {
        return FeignPythonChatbotClient(settings.pythonUrl, mapper, Client.Default(null, null), Request.Options())
    }

    // 수집기가 모은 보호소 공공정보를 챗봇 읽기 트랜잭션(animalCommandJpaTransactionManager)에서 읽는다.
    @Bean
    fun animalChatbotShelterPublicInformation(
        @Qualifier("animalCommandRepositoryFactory") repositories: JpaRepositoryFactory,
    ): ShelterPublicInformationRepository {
        return repositories.getRepository(ShelterPublicInformationRepository::class.java)
    }

    @Bean
    fun animalChatbotService(
        animals: AnimalRepository,
        sessions: ChatbotSessionRepository,
        messages: ChatbotMessageRepository,
        blocks: ChatbotBlockLogRepository,
        cookies: ChatbotCookieService,
        ips: ChatbotClientIpResolver,
        hashes: ChatbotIpHashService,
        quotas: ChatbotRateLimitService,
        guard: ChatbotGuardService,
        python: PythonChatbotClient,
        settings: Settings,
        shelterPublicInformation: ShelterPublicInformationRepository,
        @Qualifier("animalChatbotReads") reads: TransactionTemplate,
        @Qualifier("animalChatbotWrites") writes: TransactionTemplate,
    ): ChatbotService {
        return ChatbotService(
            animals,
            sessions,
            messages,
            blocks,
            cookies,
            ips,
            hashes,
            quotas,
            guard,
            python,
            settings.pythonKey,
            shelterPublicInformation,
            reads,
            writes,
        )
    }

    @Bean
    fun animalChatbotController(service: ChatbotService): ChatbotController {
        return ChatbotController(service)
    }

    @Bean
    fun animalChatbotAdvice(): ChatbotExceptionHandler {
        return ChatbotExceptionHandler()
    }

    /** Was a private record; Kotlin cannot expose a private type from a public bean method, so it is public here. */
    @JvmRecord
    data class Settings(
        val pythonUrl: String,
        val pythonKey: String,
        val ipHashSecret: String,
        val redisAddress: String,
        val redisDatabase: Int,
        val redisPassword: String,
        val anonymousTenMinutes: Long,
        val anonymousDaily: Long,
        val ipTenMinutes: Long,
        val ipDaily: Long,
        val globalDaily: Long,
    ) {
        override fun toString(): String {
            return "AnimalChatbotSettings[redacted]"
        }
    }

    private companion object {
        const val INVALID = "Animal chatbot requires explicit valid configuration"
    }
}
