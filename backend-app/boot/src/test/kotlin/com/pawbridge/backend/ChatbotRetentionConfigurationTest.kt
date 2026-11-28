package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.JdbcChatbotRetention
import com.pawbridge.backend.boot.configuration.ChatbotRetentionConfiguration
import com.pawbridge.backend.boot.configuration.ChatbotRetentionConfiguration.ChatbotRetentionJob
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@ExtendWith(OutputCaptureExtension::class)
class ChatbotRetentionConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `정리 플래그가 없거나 꺼져 있을 때__조립하면__보관 기간 정리와 스케줄링 빈을 등록하지 않는다`(flag: String) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(ChatbotRetentionConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.jobs.cleanup-enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(ChatbotRetentionJob::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ThreadPoolTaskScheduler::class.java)).isEmpty()
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
        }
    }

    @Test
    fun `정리가 켜지고 기존 쓰기 입력이 있을 때__조립하면__전용 스케줄러에 15분 뒤 시작하는 6시간 간격 작업을 하나 등록한다`() {
        // given
        val runner = enabled()

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(ChatbotRetentionJob::class.java)).hasSize(1)
            val scheduler = context.getBean("chatbotRetentionScheduler", ThreadPoolTaskScheduler::class.java)
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
            assertThat(context.beanFactory.getDependenciesForBean("chatbotRetentionScheduler")).contains("writeDataSource")
            val processor = context.getBean(ScheduledAnnotationBeanPostProcessor::class.java)
            assertThat(processor.scheduledTasks).hasSize(1)
            val task = processor.scheduledTasks.iterator().next().task as FixedDelayTask
            assertThat(task.initialDelayDuration).isEqualTo(Duration.ofMinutes(15))
            assertThat(task.intervalDuration).isEqualTo(Duration.ofHours(6))
        }
    }

    @Test
    fun `비공개 오류 상세가 주어졌을 때__스케줄러가 실패를 처리하면__실패 유형만 기록한다`(output: CapturedOutput) {
        enabled().run { context ->
            val scheduler = context.getBean("chatbotRetentionScheduler", ThreadPoolTaskScheduler::class.java)
            scheduler.schedule(
                { throw IllegalStateException("private question DELETE FROM chatbot_messages", RuntimeException("private-cause")) },
                Instant.now(),
            ).get(5, TimeUnit.SECONDS)

            assertThat(output.all).contains("Chatbot retention cleanup failed (IllegalStateException)")
                .doesNotContain("private question", "DELETE FROM", "private-cause")
        }
    }

    @Test
    fun `지울 기록이 묶음보다 많을 때__실행하면__1년 전 시각으로 메시지, 빈 세션, 제한 기록 순서로 묶음이 덜 찰 때까지 지운다`() {
        // given
        val retention = mockk<JdbcChatbotRetention>()
        val clock = Clock.fixed(Instant.parse("2027-12-01T03:00:00Z"), ZoneId.of("Asia/Seoul"))
        val cutoff = LocalDateTime.of(2026, 12, 1, 12, 0)
        every { retention.deleteMessagesBefore(cutoff, 1000) } returnsMany listOf(1000, 1000, 3)
        every { retention.deleteEmptySessionsBefore(cutoff, 1000) } returns 2
        every { retention.deleteBlockLogsBefore(cutoff, 1000) } returns 0
        val transactions = Transactions()

        // when
        ChatbotRetentionJob(retention, TransactionTemplate(transactions), clock).deleteExpired()

        // then
        verifyOrder {
            retention.deleteMessagesBefore(cutoff, 1000)
            retention.deleteMessagesBefore(cutoff, 1000)
            retention.deleteMessagesBefore(cutoff, 1000)
            retention.deleteEmptySessionsBefore(cutoff, 1000)
            retention.deleteBlockLogsBefore(cutoff, 1000)
        }
        // 묶음마다 따로 커밋한다.
        assertThat(transactions.commits).isEqualTo(5)
    }

    @Test
    fun `메시지가 계속 꽉 찬 묶음으로 지워질 때__실행하면__한 번에 50묶음까지만 지우고 다음 단계로 넘어간다`() {
        // given
        val retention = mockk<JdbcChatbotRetention>()
        every { retention.deleteMessagesBefore(any(), 1000) } returns 1000
        every { retention.deleteEmptySessionsBefore(any(), 1000) } returns 0
        every { retention.deleteBlockLogsBefore(any(), 1000) } returns 0

        // when
        ChatbotRetentionJob(retention, TransactionTemplate(Transactions()), Clock.systemDefaultZone()).deleteExpired()

        // then
        io.mockk.verify(exactly = 50) { retention.deleteMessagesBefore(any(), 1000) }
        io.mockk.verify(exactly = 1) { retention.deleteEmptySessionsBefore(any(), 1000) }
        io.mockk.verify(exactly = 1) { retention.deleteBlockLogsBefore(any(), 1000) }
    }

    private fun enabled(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withUserConfiguration(ChatbotRetentionConfiguration::class.java)
            .withPropertyValues("pawbridge.jobs.cleanup-enabled=true")
            .withInitializer { context ->
                context.beanFactory.registerSingleton("writeDataSource", Any())
                context.beanFactory.registerSingleton("writeJdbc", mockk<JdbcTemplate>())
                context.beanFactory.registerSingleton("writeTransactionManager", mockk<JdbcTransactionManager>())
            }
    }

    private class Transactions : AbstractPlatformTransactionManager() {
        var commits = 0

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {}

        override fun doCommit(status: DefaultTransactionStatus) {
            commits += 1
        }

        override fun doRollback(status: DefaultTransactionStatus) {}
    }
}
