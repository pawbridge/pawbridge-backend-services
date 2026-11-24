package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.javaIsBlank
import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatStreamHints
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatSignals
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.internal.chat.web.MemberChatController
import com.pawbridge.backend.community.internal.chat.web.MemberChatExceptionHandler
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions
import com.pawbridge.backend.community.internal.chat.web.MemberChatSocketController
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry
import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import java.time.Clock
import java.time.Duration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpUserRegistry

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["pawbridge.chat.enabled"], havingValue = "true")
class MemberChatTransportConfiguration {
    @Bean
    fun memberChatRedisConnectionFactory(
        @Value("\${spring.data.redis.host}") host: String,
        @Value("\${spring.data.redis.port:6379}") port: Int,
        @Value("\${spring.data.redis.database:0}") database: Int,
        @Value("\${spring.data.redis.username:}") username: String,
        @Value("\${spring.data.redis.password:}") password: String,
    ): LettuceConnectionFactory {
        if (javaIsBlank(host) || port < 1 || port > 65535 || database < 0) {
            throw IllegalArgumentException("Explicit chat Redis settings required")
        }
        val server = RedisStandaloneConfiguration(host, port)
        server.database = database
        if (!javaIsBlank(username)) {
            server.username = username
        }
        if (password.isNotEmpty()) {
            server.password = RedisPassword.of(password)
        }
        val client = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .shutdownTimeout(Duration.ofSeconds(2))
            .clientOptions(
                ClientOptions.builder()
                    .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
                    .build(),
            )
            .build()
        return LettuceConnectionFactory(server, client)
    }

    @Bean
    fun memberChatRedis(
        @Qualifier("memberChatRedisConnectionFactory") factory: LettuceConnectionFactory,
    ): StringRedisTemplate {
        return StringRedisTemplate(factory)
    }

    @Bean
    fun memberChatTickets(
        @Qualifier("memberChatRedis") redis: StringRedisTemplate,
        @Qualifier("readClock") clock: Clock,
        @Value("\${pawbridge.chat.namespace}") namespace: String,
    ): MemberChatTickets {
        return MemberChatTickets(redis, clock, namespace)
    }

    @Bean
    fun memberChatSessions(
        @Qualifier("memberChatRedis") redis: StringRedisTemplate,
        chat: ObjectProvider<MemberChat>,
        @Qualifier("readClock") clock: Clock,
        @Value("\${pawbridge.chat.namespace}") namespace: String,
    ): MemberChatSessions {
        return MemberChatSessions(redis, chat, clock, namespace)
    }

    // 채팅은 쪽지 패키지를 직접 참조하지 않는다. 쪽지 알림 스트림과는 여기서만 잇는다.
    @Bean
    fun memberChatStreamHints(streams: PrivateNoteStreamRegistry): MemberChatStreamHints {
        return MemberChatStreamHints { signal -> streams.publishChat(signal.memberId, signal) }
    }

    @Bean
    fun memberChatSignals(
        @Qualifier("memberChatRedis") redis: StringRedisTemplate,
        messaging: SimpMessagingTemplate,
        sessions: MemberChatSessions,
        mapper: ObjectMapper,
        users: SimpUserRegistry,
        streams: MemberChatStreamHints,
        @Value("\${pawbridge.chat.namespace}") namespace: String,
    ): MemberChatSignals {
        return MemberChatSignals(redis, messaging, sessions, mapper, users, streams, namespace)
    }

    @Bean
    fun memberChatController(chat: MemberChat, tickets: MemberChatTickets): MemberChatController {
        return MemberChatController(chat, tickets)
    }

    @Bean
    fun memberChatSocketController(
        chat: MemberChat,
        messaging: SimpMessagingTemplate,
        @Qualifier("readClock") clock: Clock,
    ): MemberChatSocketController {
        return MemberChatSocketController(chat, messaging, clock)
    }

    @Bean
    fun memberChatExceptionHandler(): MemberChatExceptionHandler {
        return MemberChatExceptionHandler()
    }
}
