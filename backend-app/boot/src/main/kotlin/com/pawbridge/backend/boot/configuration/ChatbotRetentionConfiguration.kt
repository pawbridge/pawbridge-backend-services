package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.JdbcChatbotRetention
import java.time.Clock
import java.time.LocalDateTime
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler

/**
 * AI 입양 도우미 기록의 보관 기간(질문일부터 1년)이 지나면 지운다. 개인정보처리방침에 적은 기간이다.
 * 쪽지·채팅 만료 정리와 같은 `pawbridge.jobs.cleanup-enabled=true`에서만 조립하며, 챗봇 기능 플래그와는 별개다
 * (기능을 꺼도 이미 쌓인 기록은 기간이 지나면 지워야 한다).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.jobs", name = ["cleanup-enabled"], havingValue = "true", matchIfMissing = false)
@EnableScheduling
class ChatbotRetentionConfiguration {
    @Bean
    @DependsOn("writeDataSource")
    fun chatbotRetentionScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("chatbot-retention-")
        scheduler.setAcceptTasksAfterContextClose(false)
        scheduler.setWaitForTasksToCompleteOnShutdown(false)
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        scheduler.setRemoveOnCancelPolicy(true)
        scheduler.setAwaitTerminationSeconds(5)
        scheduler.setErrorHandler(
            ErrorHandler { failure -> log.warn("Chatbot retention cleanup failed ({})", failure.javaClass.simpleName) },
        )
        return scheduler
    }

    @Bean
    fun chatbotRetentionJob(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): ChatbotRetentionJob {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        // 기록 시각은 JPA 감사가 서버 기본 시간대의 LocalDateTime으로 남기므로 같은 기준으로 자른다.
        return ChatbotRetentionJob(JdbcChatbotRetention(jdbc), transaction, Clock.systemDefaultZone())
    }

    class ChatbotRetentionJob(
        private val retention: JdbcChatbotRetention,
        private val transaction: TransactionTemplate,
        private val clock: Clock,
    ) {
        /** 시작 15분 뒤부터 6시간마다. 한 번에 다 못 지우면 다음 실행이 이어서 지운다. */
        @Scheduled(scheduler = "chatbotRetentionScheduler", initialDelay = 900000, fixedDelay = 21600000)
        fun deleteExpired() {
            val cutoff = LocalDateTime.now(clock).minusYears(RETENTION_YEARS)
            drain { retention.deleteMessagesBefore(cutoff, BATCH_SIZE) }
            drain { retention.deleteEmptySessionsBefore(cutoff, BATCH_SIZE) }
            drain { retention.deleteBlockLogsBefore(cutoff, BATCH_SIZE) }
        }

        /** 작은 묶음을 각자 짧은 트랜잭션으로 지운다. 묶음이 덜 찼으면 남은 행이 없다는 뜻이다. */
        private fun drain(batch: () -> Int) {
            for (round in 0 until MAX_BATCHES_PER_RUN) {
                val deleted = transaction.execute { _ -> batch() }
                if (deleted == null || deleted < BATCH_SIZE) {
                    return
                }
            }
        }

        companion object {
            const val RETENTION_YEARS: Long = 1
            const val BATCH_SIZE: Int = 1000
            const val MAX_BATCHES_PER_RUN: Int = 50
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ChatbotRetentionConfiguration::class.java)
    }
}
