package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.JdbcChatbotMemberCleanup
import com.pawbridge.backend.community.api.ContactMemberCleanup
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal
import com.pawbridge.backend.community.internal.note.application.CleanupContactMember
import com.pawbridge.backend.community.internal.note.application.ContactMemberCleanupStore
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcContactMemberCleanupStore
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import com.pawbridge.backend.user.internal.deletion.infrastructure.JdbcUserDeletion
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Synchronous SQL cleanup remains single-owned; chat and note hints follow its commit. */
@Configuration(proxyBeanMethods = false)
class MemberCleanupConfiguration(
    @Value("\${pawbridge.chat.enabled:\${MEMBER_CHAT_ENABLED:false}}") private val chatEnabled: Boolean,
) {
    @Bean
    fun contactMemberCleanupStore(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        streams: PrivateNoteStreamRegistry,
        chatHints: ObjectProvider<MemberChatHints>,
    ): ContactMemberCleanupStore {
        val chat: MemberChatHints? = if (chatEnabled) chatHints.getIfAvailable() else null
        if (chatEnabled && chat == null) {
            throw IllegalStateException("Member cleanup with chat enabled requires verified Redis withdrawal wiring")
        }
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        return JdbcContactMemberCleanupStore(jdbc, transaction) { memberId ->
            if (chat != null) {
                try {
                    chat.publish(Signal(memberId, "WITHDRAWN", null, 0))
                } catch (failure: RuntimeException) {
                    LoggerFactory.getLogger(MemberCleanupConfiguration::class.java)
                        .warn("Chat withdrawal hint unavailable ({})", failure.javaClass.simpleName)
                }
            }
            streams.closeMember(memberId)
            streams.resyncAll()
        }
    }

    @Bean
    fun contactMemberCleanup(members: ContactMemberQuery, store: ContactMemberCleanupStore): ContactMemberCleanup {
        return CleanupContactMember(members, store)
    }

    /**
     * Starting a deletion revokes the member's refresh sessions together with the pending marker. The retryable
     * cleanup step then withdraws notes and chats and clears the member id from chatbot conversations (the
     * conversations stay until their retention ends, no longer linked to the member).
     */
    @Bean
    fun userDeletion(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        cleanup: ContactMemberCleanup,
        sessions: ObjectProvider<UserSessionRevoker>,
    ): UserDeletion {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        val revoker: UserSessionRevoker = sessions.getIfAvailable { UserSessionRevoker.NONE }
        val chatbotMembers = JdbcChatbotMemberCleanup(jdbc)
        return JdbcUserDeletion(
            jdbc,
            transaction,
            { memberId ->
                cleanup.remove(memberId)
                chatbotMembers.forget(memberId)
            },
            revoker,
        )
    }

    @Bean
    fun adminUserDeletionController(deletion: UserDeletion): AdminUserDeletionController {
        return AdminUserDeletionController(deletion)
    }

    @Bean
    fun adminUserDeletionExceptionHandler(): AdminUserDeletionExceptionHandler {
        return AdminUserDeletionExceptionHandler()
    }
}
