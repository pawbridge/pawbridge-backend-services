package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchExecutionRecovery
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchProperties
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsSyncPlanFactory
import com.pawbridge.backend.animal.internal.apms.infrastructure.writer.AnimalItemWriter
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Gender
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.NeuterStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.repository.dao.DefaultExecutionContextSerializer
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean
import org.springframework.batch.item.Chunk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The APMS collection storage: the chunk writer (insert and direct update in one writer transaction with the search
 * documents), the batch metadata tables behind the `pawbridge_animal.BATCH_` prefix, the recovery of an orphaned execution,
 * and the session lock that keeps a second collection from starting.
 */
class ApmsCollectionJpaContractTest : JpaContractBase() {
    @Autowired
    @Qualifier("animalCommandAnimals")
    lateinit var animalRepository: AnimalRepository

    @Autowired
    @Qualifier("animalCommandShelters")
    lateinit var shelterRepository: ShelterRepository

    @Autowired
    @Qualifier("animalCommandSearch")
    lateinit var search: SearchDocumentWriter

    @Autowired
    @Qualifier("animalCommandJpaTransactionManager")
    lateinit var animalManager: JpaTransactionManager

    private lateinit var jobRepository: JobRepository
    private lateinit var explorer: JobExplorer

    @BeforeEach
    fun build() {
        val serializer = DefaultExecutionContextSerializer()
        val repositoryFactory = JobRepositoryFactoryBean()
        repositoryFactory.setDataSource(writeSource)
        repositoryFactory.transactionManager = animalManager
        repositoryFactory.setDatabaseType("POSTGRES")
        repositoryFactory.setTablePrefix("pawbridge_animal.BATCH_")
        repositoryFactory.setIsolationLevelForCreate("ISOLATION_SERIALIZABLE")
        repositoryFactory.setSerializer(serializer)
        repositoryFactory.afterPropertiesSet()
        jobRepository = repositoryFactory.getObject()
        val explorerFactory = JobExplorerFactoryBean()
        explorerFactory.setDataSource(writeSource)
        explorerFactory.setTransactionManager(animalManager)
        explorerFactory.setTablePrefix("pawbridge_animal.BATCH_")
        explorerFactory.setSerializer(serializer)
        explorerFactory.afterPropertiesSet()
        explorer = explorerFactory.getObject()
        seedShelter(SHELTER, "CR-APMS")
    }

    private fun recovery() = ApmsBatchRecoveryFactory.create(explorer, jobRepository, animalManager)

    private fun runningExecution(minutesSinceUpdate: Long): Long {
        val execution = jobRepository.createJobExecution(JOB, JobParameters())
        execution.setStatus(BatchStatus.STARTED)
        execution.setStartTime(java.time.LocalDateTime.now())
        jobRepository.update(execution)
        execute(
            "UPDATE pawbridge_animal.batch_job_execution SET last_updated = now() - make_interval(mins => ?), start_time = now() - make_interval(mins => ?) WHERE job_execution_id = ?",
            minutesSinceUpdate.toInt(), minutesSinceUpdate.toInt(), execution.id,
        )
        return execution.id
    }

    private fun executionStatus(id: Long) =
        scalar("SELECT status FROM pawbridge_animal.batch_job_execution WHERE job_execution_id = ?", id)

    private fun animalFrom(id: Long?, notice: String, desertion: String, breed: String, status: AnimalStatus = AnimalStatus.PROTECT): Animal =
        Animal.builder()
            .id(id).apmsDesertionNo(desertion).apmsNoticeNo(notice).species(Species.DOG).breed(breed).birthYear(2021).color("흰색")
            .gender(Gender.FEMALE).neuterStatus(NeuterStatus.YES).apmsProcessState("보호중")
            .noticeStartDate(LocalDate.parse("2026-03-02")).noticeEndDate(LocalDate.parse("2026-03-22"))
            .happenDate(LocalDate.parse("2026-03-01")).happenPlace("수집 장소").imageUrl("https://source.invalid/a.jpg")
            .shelter(shelterRepository.findByCareRegNo("CR-APMS").get()).status(status).apiSource(ApiSource.APMS_ANIMAL)
            .favoriteCount(0).build()

    @Test
    fun `새 동물과 기존 동물이 한 묶음일 때__쓰면__새 동물은 넣고 기존 동물은 수집 필드만 바꾸고 둘 다 검색 문서를 만든다`() {
        // given
        seedAnimal(EXISTING, SHELTER, description = "직접 쓴 설명", noticeNo = "OLD-NOTICE")
        execute("UPDATE pawbridge_animal.animals SET apms_desertion_no = 'D-EXISTING', favorite_count = 7, api_source = 'APMS_ANIMAL' WHERE id = ?", EXISTING)
        val writer = AnimalItemWriter(search, animalRepository)
        val fresh = animalFrom(null, "N-NEW", "D-NEW", "새 품종")
        val changed = animalFrom(EXISTING, "OLD-NOTICE", "D-EXISTING", "바뀐 품종")

        // when
        TransactionTemplate(animalManager).executeWithoutResult { writer.write(Chunk(listOf(fresh, changed))) }

        // then
        val newId = scalar("SELECT id FROM pawbridge_animal.animals WHERE apms_desertion_no = 'D-NEW'")!!.toLong()
        assertThat(scalar("SELECT breed FROM pawbridge_animal.animals WHERE id = ?", newId)).isEqualTo("새 품종")
        assertThat(row("pawbridge_animal.animals", "id", EXISTING).let { listOf(it.get("breed").asText(), it.get("color").asText(), it.get("description").asText(), it.get("favorite_count").asInt()) })
            .isEqualTo(listOf("바뀐 품종", "흰색", "직접 쓴 설명", 7))
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animal_search_documents WHERE animal_id IN (?, ?)", newId, EXISTING)).isEqualTo(2)
    }

    @Test
    fun `상태가 바뀐 동물과 같은 상태인 동물과 미상이 된 동물이 한 묶음일 때__쓰면__바뀐 동물만 이전 상태와 시각을 쓰고 나머지는 이전 기록을 둔다`() {
        // given
        seedAnimal(EXISTING, SHELTER, status = "NOTICE")
        seedAnimal(SAME, SHELTER, status = "PROTECT")
        seedAnimal(TO_UNKNOWN, SHELTER, status = "PROTECT")
        execute(
            "UPDATE pawbridge_animal.animals SET previous_status = 'NOTICE', status_changed_at = '2026-10-01 00:00:00+00' WHERE id IN (?, ?)",
            SAME, TO_UNKNOWN,
        )
        val now = Instant.parse("2026-11-02T03:00:00.123456Z")
        val writer = AnimalItemWriter(search, animalRepository, Clock.fixed(now, ZoneOffset.UTC))
        val chunk = Chunk(
            listOf(
                animalFrom(EXISTING, "N-$EXISTING", "D-1", "믹스견", AnimalStatus.PROTECT),
                animalFrom(SAME, "N-$SAME", "D-2", "믹스견", AnimalStatus.PROTECT),
                animalFrom(TO_UNKNOWN, "N-$TO_UNKNOWN", "D-3", "믹스견", AnimalStatus.UNKNOWN),
            ),
        )

        // when
        TransactionTemplate(animalManager).executeWithoutResult { writer.write(chunk) }

        // then
        val stored = listOf(EXISTING, SAME, TO_UNKNOWN).map { id ->
            listOf(
                scalar("SELECT status FROM pawbridge_animal.animals WHERE id = ?", id),
                scalar("SELECT previous_status FROM pawbridge_animal.animals WHERE id = ?", id),
                scalar("SELECT to_char(status_changed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') FROM pawbridge_animal.animals WHERE id = ?", id),
            )
        }
        assertThat(stored).containsExactly(
            listOf("PROTECT", "NOTICE", "2026-11-02 03:00:00.123456"),
            listOf("PROTECT", "NOTICE", "2026-10-01 00:00:00.000000"),
            listOf("UNKNOWN", "NOTICE", "2026-10-01 00:00:00.000000"),
        )
    }

    @Test
    fun `쓰기 도중 호출자 트랜잭션이 롤백될 때__묶음을 쓰면__새 동물도 검색 문서도 남지 않는다`() {
        // given
        val writer = AnimalItemWriter(search, animalRepository)
        val before = ContractPostgres.snapshot()

        // when
        TransactionTemplate(animalManager).executeWithoutResult { status ->
            writer.write(Chunk(listOf(animalFrom(null, "N-NEW", "D-NEW", "새 품종"))))
            status.setRollbackOnly()
        }

        // then
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `배치 메타데이터 접두사가 보호소 스키마 표를 가리킬 때__실행을 만들고 탐색하면__pawbridge_animal의 BATCH 표에만 저장한다`() {
        // given
        val id = runningExecution(minutesSinceUpdate = 0)

        // when
        val found = explorer.findRunningJobExecutions(JOB).map { it.id }

        // then
        assertThat(found).isEqualTo(listOf(id))
        assertThat(count("SELECT count(*) FROM pawbridge_animal.batch_job_instance WHERE job_name = ?", JOB)).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'batch_job%' AND table_schema <> 'pawbridge_animal'"))
            .`as`("No batch metadata table exists outside the animal schema").isZero()
    }

    @Test
    fun `최근에 갱신된 실행과 오래된 실행이 있을 때__복구하면__최근 것은 거부하고 열 분을 넘은 것만 실패로 닫는다`() {
        // given
        val recent = runningExecution(minutesSinceUpdate = 9)

        // when
        val rejected = runCatching { recovery().recoverOrReject(JOB) }.exceptionOrNull()
        execute("UPDATE pawbridge_animal.batch_job_execution SET last_updated = now() - interval '11 minutes' WHERE job_execution_id = ?", recent)
        val closed = recovery().recoverOrReject(JOB)

        // then
        assertThat(rejected).isInstanceOf(ApmsBatchExecutionRecovery.ActiveExecutionMetadataException::class.java)
        assertThat(closed).isEqualTo(1)
        assertThat(executionStatus(recent)).isEqualTo("FAILED")
        assertThat(recovery().recoverOrReject(JOB)).`as`("Nothing is left to recover").isZero()
    }

    @Test
    fun `다른 세션이 수집 잠금을 쥐고 있을 때__수집을 시작하면__이미 실행 중이고 작업은 시작하지 않는다`() {
        // given
        val launcher = mockk<JobLauncher>()
        val job = mockk<Job>().also { every { it.name } returns JOB }
        val runner = ApmsBatchRunner(writeSource, recovery(), launcher, job, planFactory())

        // when
        val failure = ContractPostgres.adminConnection { holder ->
            holder.createStatement().use { it.execute("SELECT pg_advisory_lock($NAMESPACE, 1)") }
            runCatching { runner.run() }.exceptionOrNull()
        }

        // then
        assertThat(failure).isInstanceOf(ApmsBatchRunner.AlreadyRunningException::class.java)
        verify(exactly = 0) { launcher.run(any(), any()) }
    }

    @Test
    fun `수집 잠금이 비어 있을 때__수집을 시작하면__작업을 한 번 실행하고 끝나면 실패해도 잠금을 푼다`() {
        // given
        val launcher = mockk<JobLauncher>()
        val job = mockk<Job>().also { every { it.name } returns JOB }
        every { launcher.run(any(), any()) } returns JobExecution(1L) andThenThrows IllegalStateException("job failed")
        val runner = ApmsBatchRunner(writeSource, recovery(), launcher, job, planFactory())

        // when
        val done = runner.run()
        val failure = runCatching { runner.run() }.exceptionOrNull()

        // then
        assertThat(done.id).isEqualTo(1L)
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 2) { launcher.run(any(), any()) }
        assertThat(lockIsFree()).isTrue()
    }

    @Test
    fun `최근에 갱신된 실행이 남아 있을 때__수집을 시작하면__사용할 수 없음이고 작업은 시작하지 않으며 잠금은 풀린다`() {
        // given
        runningExecution(minutesSinceUpdate = 1)
        val launcher = mockk<JobLauncher>()
        val job = mockk<Job>().also { every { it.name } returns JOB }
        val runner = ApmsBatchRunner(writeSource, recovery(), launcher, job, planFactory())

        // when
        val failure = runCatching { runner.run() }.exceptionOrNull()

        // then
        assertThat(failure).isInstanceOf(ApmsBatchRunner.UnavailableException::class.java)
        verify(exactly = 0) { launcher.run(any(), any()) }
        assertThat(lockIsFree()).isTrue()
    }

    private fun planFactory() = ApmsSyncPlanFactory(animalRepository, explorer, Clock.system(ZoneId.of("Asia/Seoul")))

    /** True when another session can take the collection lock, which proves the runner released its own. */
    private fun lockIsFree(): Boolean = ContractPostgres.adminConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_try_advisory_lock($NAMESPACE, 1)").use { rows ->
                rows.next()
                val free = rows.getBoolean(1)
                if (free) {
                    statement.execute("SELECT pg_advisory_unlock($NAMESPACE, 1)")
                }
                free
            }
        }
    }

    private object ApmsBatchRecoveryFactory {
        fun create(explorer: JobExplorer, repository: JobRepository, manager: JpaTransactionManager) =
            ApmsBatchExecutionRecovery(explorer, repository, java.time.Clock.systemDefaultZone(), ApmsBatchProperties(), manager, 15)
    }

    private companion object {
        const val JOB = "apmsAnimalSyncJob"
        const val NAMESPACE = 0x50415742
        const val SHELTER = 8_600_001L
        const val EXISTING = 8_600_101L
        const val SAME = 8_600_102L
        const val TO_UNKNOWN = 8_600_103L
    }
}
