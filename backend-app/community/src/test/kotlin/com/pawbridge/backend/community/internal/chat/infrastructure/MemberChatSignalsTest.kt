package com.pawbridge.backend.community.internal.chat.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels
import com.pawbridge.backend.community.internal.chat.application.MemberChatStreamHints
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions
import com.pawbridge.backend.community.support.TestFaker
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.data.redis.connection.DefaultMessage
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpSession
import org.springframework.messaging.simp.user.SimpUser
import org.springframework.messaging.simp.user.SimpUserRegistry
import java.util.LinkedHashSet

class MemberChatSignalsTest {
    private val messaging = mockk<SimpMessagingTemplate>()
    private val registry = mockk<SimpUserRegistry>()
    private val sessions = mockk<MemberChatSessions>()
    private val streams = mockk<MemberChatStreamHints>()
    private val mapper = ObjectMapper()
    private val signals = MemberChatSignals(
        mockk<StringRedisTemplate>(),
        messaging,
        sessions,
        mapper,
        registry,
        streams,
        "synthetic-test",
    )

    @Test
    fun `내 연결이 둘이고 첫 전송이 실패할 때__메시지 신호가 도착하면__다른 연결로 전달한다`() {
        // given
        val first = mockk<SimpSession>()
        val second = mockk<SimpSession>()
        val member = mockk<SimpUser>()
        val signal = MemberChatModels.Signal(7, "MESSAGE", TestFaker.uuid(), 3)

        every { first.id } returns "first-session"
        every { second.id } returns "second-session"
        every { member.sessions } returns LinkedHashSet(listOf(first, second))
        every { registry.getUser(any<String>()) } returns member
        justRun { streams.deliver(any()) }
        every {
            messaging.convertAndSendToUser("first-session", "/queue/chat", signal, any<Map<String, Any>>())
        } throws IllegalStateException("synthetic send failure")
        justRun {
            messaging.convertAndSendToUser("second-session", "/queue/chat", signal, any<Map<String, Any>>())
        }

        // when
        signals.onMessage(DefaultMessage(ByteArray(0), mapper.writeValueAsBytes(signal)), null)

        // then
        verify(exactly = 1) {
            messaging.convertAndSendToUser("first-session", "/queue/chat", signal, any<Map<String, Any>>())
        }
        verify(exactly = 1) {
            messaging.convertAndSendToUser(
                "second-session",
                "/queue/chat",
                signal,
                match<Map<String, Any>> { headers -> "second-session" == SimpMessageHeaderAccessor.getSessionId(headers) },
            )
        }
    }

    @Test
    fun `회원에게 로컬 연결이 없을 때__메시지 신호가 도착하면__다른 회원에게 전달하지 않는다`() {
        // given
        val signal = MemberChatModels.Signal(7, "MESSAGE", TestFaker.uuid(), 3)

        every { registry.getUser(any<String>()) } returns null
        justRun { streams.deliver(any()) }

        // when
        signals.onMessage(DefaultMessage(ByteArray(0), mapper.writeValueAsBytes(signal)), null)

        // then
        verify(exactly = 1) { registry.getUser("7") }
        confirmVerified(registry, messaging)
    }

    @ParameterizedTest
    @ValueSource(strings = ["SENT", "MESSAGE", "READ", "HIDDEN"])
    fun `회원에게 소켓 연결이 없을 때__방 신호가 도착하면__알림 스트림에는 같은 신호를 한 번 넘긴다`(kind: String) {
        // given
        val signal = MemberChatModels.Signal(7, kind, TestFaker.uuid(), 3)

        every { registry.getUser(any<String>()) } returns null
        justRun { streams.deliver(any()) }

        // when
        signals.onMessage(DefaultMessage(ByteArray(0), mapper.writeValueAsBytes(signal)), null)

        // then
        verify(exactly = 1) { streams.deliver(signal) }
        confirmVerified(streams, messaging)
    }

    @Test
    fun `알림 스트림 전달이 실패할 때__메시지 신호가 도착하면__소켓 연결에는 그대로 전달한다`() {
        // given
        val session = mockk<SimpSession>()
        val member = mockk<SimpUser>()
        val signal = MemberChatModels.Signal(7, "MESSAGE", TestFaker.uuid(), 3)

        every { session.id } returns "only-session"
        every { member.sessions } returns LinkedHashSet(listOf(session))
        every { registry.getUser(any<String>()) } returns member
        every { streams.deliver(any()) } throws IllegalStateException("synthetic stream failure")
        justRun {
            messaging.convertAndSendToUser("only-session", "/queue/chat", signal, any<Map<String, Any>>())
        }

        // when
        signals.onMessage(DefaultMessage(ByteArray(0), mapper.writeValueAsBytes(signal)), null)

        // then
        verify(exactly = 1) {
            messaging.convertAndSendToUser("only-session", "/queue/chat", signal, any<Map<String, Any>>())
        }
    }

    @Test
    fun `탈퇴 신호이거나 방이 없는 신호일 때__신호가 도착하면__알림 스트림으로 넘기지 않는다`() {
        // given
        val withdrawn = MemberChatModels.Signal(7, "WITHDRAWN", null, 0)
        val roomless = MemberChatModels.Signal(7, "MESSAGE", null, 3)
        val unknown = MemberChatModels.Signal(7, "TYPING", TestFaker.uuid(), 3)

        justRun { sessions.closeMember(any()) }

        // when
        for (signal in listOf(withdrawn, roomless, unknown)) {
            signals.onMessage(DefaultMessage(ByteArray(0), mapper.writeValueAsBytes(signal)), null)
        }

        // then
        verify(exactly = 1) { sessions.closeMember(7) }
        confirmVerified(streams, messaging, registry)
    }
}
