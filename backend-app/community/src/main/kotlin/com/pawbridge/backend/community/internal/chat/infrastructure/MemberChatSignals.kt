package com.pawbridge.backend.community.internal.chat.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal
import com.pawbridge.backend.community.internal.chat.application.MemberChatStreamHints
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpUserRegistry

open class MemberChatSignals(
    private val redis: StringRedisTemplate,
    private val messaging: SimpMessagingTemplate,
    private val sessions: MemberChatSessions,
    private val mapper: ObjectMapper,
    private val registry: SimpUserRegistry,
    private val streams: MemberChatStreamHints,
    @Value("\${pawbridge.chat.namespace}") namespace: String,
) : MessageListener, MemberChatHints {

    private val channel: String = "pawbridge:" + namespace + ":chat:signals"

    open fun channel(): String = channel

    override fun publish(signal: Signal) {
        try {
            redis.convertAndSend(channel, mapper.writeValueAsString(signal))
        } catch (unavailable: Exception) {
            // The DB transaction is already committed. Browser reconciliation repairs a lost signal.
            log.warn("Chat signal delivery unavailable; REST reconciliation required")
        }
    }

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            if (message.body.size > 1024) {
                return
            }
            val signal = mapper.readValue(String(message.body, StandardCharsets.UTF_8), Signal::class.java)
            if (signal.memberId <= 0) {
                return
            }
            // java.util.List.of(...).contains(null) throws NullPointerException (logged below); listOf would not.
            if ("WITHDRAWN" == signal.kind) {
                sessions.closeMember(signal.memberId)
            } else if (java.util.List.of("SENT", "MESSAGE", "READ", "HIDDEN").contains(signal.kind) &&
                signal.roomId != null &&
                signal.sequence >= 0
            ) {
                // 채팅 화면 밖의 탭은 웹소켓 없이 알림 스트림으로 같은 신호를 받는다.
                // 소켓 연결이 없는 회원에게도 보내야 하므로 아래 조회보다 먼저 보낸다.
                try {
                    streams.deliver(signal)
                } catch (unavailable: RuntimeException) {
                    // 스트림 전달 실패가 소켓 전달을 막으면 안 된다.
                    log.warn("Chat stream signal delivery unavailable; REST reconciliation required")
                }
                val member = registry.getUser(signal.memberId.toString())
                if (member == null) {
                    return
                }
                for (session in member.sessions) {
                    // Each ordered user-destination send needs its own mutable headers.
                    // Reuse the session-targeted receipt path instead of one fanout message.
                    val headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE)
                    headers.sessionId = session.id
                    headers.setLeaveMutable(true)
                    try {
                        messaging.convertAndSendToUser(session.id, "/queue/chat", signal, headers.messageHeaders)
                    } catch (unavailable: RuntimeException) {
                        // A failing connection must not suppress another browser's signal.
                        log.warn("Chat session signal delivery unavailable; REST reconciliation required")
                    }
                }
            }
        } catch (unavailable: Exception) {
            log.warn("Chat signal processing unavailable; REST reconciliation required")
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(MemberChatSignals::class.java)
    }
}
