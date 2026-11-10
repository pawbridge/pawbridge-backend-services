package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.internal.shelter.application.ShelterNotFoundException
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import javax.sql.DataSource

class ModuleAnimalShelterReadsTest {
    private val source: ShelterReads = mockk<ShelterReads>()
    private val manager: PlatformTransactionManager = mockk<PlatformTransactionManager>()
    private val reads = ModuleAnimalShelterReads(source, manager)

    @Test
    fun `등록번호로 조회할 때__위임하면__원본 입력 그대로 공유 ID와 등록번호와 이름만 돌려준다`() {
        // given
        every { manager.getTransaction(any()) } answers { SimpleTransactionStatus() }
        justRun { manager.commit(any()) }
        every { source.findByCareRegNo(any()) } returns ShelterReads.Details(
            9L, "등록 번호", "보호소", null, null,
            null, null, null, null, null, null, null, null, null,
        )

        // when
        val found = reads.findByCareRegNo(" 등록 번호 ")

        // then
        assertThat(found).isEqualTo(AnimalShelterReads.Shelter(9L, "등록 번호", "보호소"))
        verify(exactly = 1) { source.findByCareRegNo(" 등록 번호 ") }
        confirmVerified(source)
    }

    @Test
    fun `모듈 경계일 때__동물 읽기 범위를 만들면__두 번째 연결 없이 읽기 전용 기본 격리와 기본 제한 시간으로 합류하거나 시작한다`() {
        // given
        every { manager.getTransaction(any()) } answers { SimpleTransactionStatus() }
        justRun { manager.commit(any()) }
        every { source.findByCareRegNo(any()) } returns null

        // when
        reads.findByCareRegNo("r")

        // then
        val definition = slot<TransactionDefinition>()
        verify(exactly = 1) { manager.getTransaction(capture(definition)) }
        assertThat(definition.captured.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
        assertThat(definition.captured.isReadOnly).isTrue()
        assertThat(definition.captured.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
        assertThat(definition.captured.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
        verify(exactly = 1) { manager.commit(any()) }
    }

    @Test
    fun `현재 원본이 등록번호 없음으로 실패할 때__위임하면__404나 빈 결과를 지어내지 않고 이용 불가를 유지한다`() {
        // given
        val failure = ShelterNotFoundException()

        every { manager.getTransaction(any()) } answers { SimpleTransactionStatus() }
        justRun { manager.rollback(any()) }
        every { source.findByCareRegNo(any()) } throws failure

        // when
        val thrown = assertThatThrownBy { reads.findByCareRegNo("r") }

        // then
        thrown.isInstanceOf(AnimalShelterReadUnavailableException::class.java)
            .hasCause(failure)
        verify(exactly = 1) { manager.rollback(any()) }
    }

    @Test
    fun `원본 본문이 비었을 때__위임하면__기존 호출자의 이용 불가 검사를 위해 null을 유지한다`() {
        // given
        every { manager.getTransaction(any()) } answers { SimpleTransactionStatus() }
        justRun { manager.commit(any()) }
        every { source.findByCareRegNo(any()) } returns null

        // when
        val found = reads.findByCareRegNo("r")

        // then
        assertThat(found).isNull()
        verify(exactly = 1) { source.findByCareRegNo("r") }
    }

    @Test
    fun `공유 풀에 승인 쓰기 트랜잭션이 있을 때__조회하면__두 번째 연결을 빌리지 않고 그 트랜잭션에 합류한다`() {
        // given
        val pool = mockk<DataSource>()
        val connection = mockk<Connection>()
        val readerManager = JdbcTransactionManager(pool)
        readerManager.isEnforceReadOnly = true
        val shared = ModuleAnimalShelterReads(source, readerManager)

        every { pool.connection } returns connection
        every { connection.autoCommit } returns true
        justRun { connection.autoCommit = any() }
        justRun { connection.commit() }
        justRun { connection.close() }
        every { source.findByCareRegNo(any()) } returns ShelterReads.Details(
            9L, "r", "보호소", null, null,
            null, null, null, null, null, null, null, null, null,
        )

        // when
        val found = TransactionTemplate(JdbcTransactionManager(pool)).execute { status -> shared.findByCareRegNo("r") }

        // then
        assertThat(found).isEqualTo(AnimalShelterReads.Shelter(9L, "r", "보호소"))
        verify(exactly = 1) { pool.connection }
        verify(exactly = 1) { connection.commit() }
    }
}
