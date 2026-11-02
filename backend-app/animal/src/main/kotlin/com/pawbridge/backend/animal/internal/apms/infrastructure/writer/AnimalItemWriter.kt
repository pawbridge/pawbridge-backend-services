package com.pawbridge.backend.animal.internal.apms.infrastructure.writer

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.AnimalStatusChange
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import org.springframework.util.StopWatch
import java.time.Clock

/**
 * Animal Entity를 DB에 저장하는 Writer
 * - id==null (신규): saveAll() → IDENTITY persist → INSERT 직행 (SELECT 없음)
 * - id!=null (기존): 묶음의 현재 상태를 한 번 읽은 뒤 @Modifying JPQL UPDATE
 *   (상태가 바뀐 동물은 관심 동물 알림용 이전 상태와 시각도 함께 쓴다)
 */
class AnimalItemWriter(
    private val searchDocuments: SearchDocumentWriter,
    private val animalRepository: AnimalRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ItemWriter<Animal> {

    @Throws(Exception::class)
    override fun write(chunk: Chunk<out Animal>) {
        if (chunk.isEmpty()) {
            log.debug("저장할 Animal이 없습니다.")
            return
        }

        try {
            val stopWatch = StopWatch("APMS Writer 측정")
            stopWatch.start("2. Animal PostgreSQL Save")

            val newAnimals: List<Animal> = chunk.items.filter { animal -> animal.id == null }

            val existingAnimals: List<Animal> = chunk.items.filter { animal -> animal.id != null }

            if (newAnimals.isNotEmpty()) {
                animalRepository.saveAll(newAnimals)
            }

            // The chunk's rows are written only by this chunk transaction, so the statuses read here are current.
            val currentStatus: Map<Long, AnimalStatus?> = if (existingAnimals.isEmpty()) {
                mapOf()
            } else {
                animalRepository.findStatuses(existingAnimals.map { it.id!! })
                    .associate { row -> (row[0] as Long) to (row[1] as AnimalStatus?) }
            }
            val now = clock.instant()

            for (existing in existingAnimals) {
                val change = AnimalStatusChange.between(currentStatus[existing.id], existing.status, now)
                animalRepository.updateAnimalFromApms(
                    existing.id,
                    existing.shelter,
                    existing.breed,
                    existing.birthYear,
                    existing.weight,
                    existing.color,
                    existing.gender,
                    existing.neuterStatus,
                    existing.specialMark,
                    existing.apmsProcessState,
                    existing.noticeStartDate,
                    existing.noticeEndDate,
                    existing.apmsUpdatedAt,
                    existing.happenDate,
                    existing.happenPlace,
                    existing.imageUrl,
                    existing.imageUrl2,
                    existing.status,
                    change?.previousStatus,
                    change?.changedAt,
                )
            }

            animalRepository.flush()
            for (animal in chunk.items) {
                searchDocuments.animal(animal.id!!)
            }

            stopWatch.stop()
            log.info(
                "Animal {} 건 처리 완료 (신규 INSERT: {}, 기존 UPDATE: {}). [성능측정] DB 소요시간 - {} ms",
                chunk.size(),
                newAnimals.size,
                existingAnimals.size,
                stopWatch.totalTimeMillis,
            )
        } catch (e: Exception) {
            log.error("Animal 저장 중 오류 발생: chunk size={}", chunk.size(), e)
            throw e
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AnimalItemWriter::class.java)
    }
}
