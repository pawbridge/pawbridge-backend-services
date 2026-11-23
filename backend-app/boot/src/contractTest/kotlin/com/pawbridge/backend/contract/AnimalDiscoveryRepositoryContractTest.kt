package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Order
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.PageRequest
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Search
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException
import com.pawbridge.backend.animal.internal.discovery.application.SearchProjectionPendingException
import com.pawbridge.backend.animal.internal.discovery.infrastructure.JdbcAnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** The public animal list, detail and expiring-soon queries on the real tables and the real search projection. */
class AnimalDiscoveryRepositoryContractTest : AnimalRepositoryContractBase() {
    private val analyzer = KoreanSearchAnalyzer()
    private lateinit var discovery: AnimalDiscovery
    private val thisYear = LocalDate.now().year

    @BeforeEach
    fun build() {
        discovery = JdbcAnimalDiscovery(source, manager, analyzer)
        seedShelter(1, name = "행복 보호소", address = "서울특별시 강남구 역삼동")
        seedShelter(2, name = "바다 보호소", address = "부산광역시 해운대구")
    }

    @AfterEach
    fun close() {
        analyzer.close()
    }

    private fun page(number: Int = 0, size: Int = 20, vararg orders: Order) = PageRequest(number, size, orders.toList(), true)

    private fun search(
        species: Species? = null, breed: String? = null, gender: Gender? = null, status: AnimalStatus? = null,
        minAge: Int? = null, maxAge: Int? = null, region: String? = null, city: String? = null, keyword: String? = null,
        noticeNo: String? = null, shelterId: Long? = null, from: String? = null, to: String? = null,
    ) = Search(species, breed, gender, null, status, minAge, maxAge, region, city, keyword, noticeNo, shelterId,
        from?.let(LocalDate::parse), to?.let(LocalDate::parse))

    private fun ids(result: AnimalDiscovery.Page) = result.content().map { it.id!! }

    @Test
    fun `공개 상태와 종료 상태가 섞여 있을 때__조건 없이 검색하면__공고 중과 보호 중만 종료일 순서로 준다`() {
        // given
        seedAnimal(10, 1, status = "PROTECT", noticeEnd = "2026-03-20")
        seedAnimal(11, 1, status = "NOTICE", noticeEnd = "2026-03-15")
        seedAnimal(12, 1, status = "ADOPTED", noticeEnd = "2026-03-10")
        seedAnimal(13, 2, status = "NOTICE", noticeEnd = "2026-03-15")

        // when
        val result = discovery.search(search(), page())

        // then
        assertThat(ids(result)).`as`("noticeEndDate ascending, then id").isEqualTo(listOf(11L, 13L, 10L))
        assertThat(result.totalElements()).isEqualTo(3)
        assertThat(result.content().first().shelterName).isEqualTo("행복 보호소")
        assertThat(result.content().first().age).isEqualTo(thisYear - 2022)
    }

    @Test
    fun `여러 조건일 때__검색하면__모든 조건을 만족하는 동물만 준다`() {
        // given
        seedAnimal(10, 1, species = "DOG", gender = "MALE", birthYear = thisYear - 2, happenDate = "2026-03-01", breed = "골든 리트리버")
        seedAnimal(11, 1, species = "CAT", gender = "MALE", birthYear = thisYear - 2, happenDate = "2026-03-01")
        seedAnimal(12, 1, species = "DOG", gender = "FEMALE", birthYear = thisYear - 2, happenDate = "2026-03-01")
        seedAnimal(13, 1, species = "DOG", gender = "MALE", birthYear = thisYear - 9, happenDate = "2026-03-01")
        seedAnimal(14, 1, species = "DOG", gender = "MALE", birthYear = thisYear - 2, happenDate = "2026-01-01")
        seedAnimal(15, 2, species = "DOG", gender = "MALE", birthYear = thisYear - 2, happenDate = "2026-03-01")

        // when
        val result = discovery.search(
            search(species = Species.DOG, gender = Gender.MALE, minAge = 1, maxAge = 3, region = "서울", city = "강남", shelterId = 1, from = "2026-02-01", to = "2026-03-31"),
            page(),
        )
        val byBreed = discovery.search(search(breed = "리트리버"), page())

        // then
        assertThat(ids(result)).isEqualTo(listOf(10L))
        assertThat(ids(byBreed)).isEqualTo(listOf(10L))
    }

    @Test
    fun `보호소 주소가 옛 이름, 줄인 이름, 통합 이름으로 섞여 있을 때__시도로 검색하면__그 시도의 보호소 동물을 모두 준다`() {
        // given
        seedShelter(3, address = "전남광주통합특별시 북구 본촌동")
        seedShelter(4, address = "전남광주통합특별시 여수시 소라면")
        seedShelter(5, address = "전라남도 나주시 금천면")
        seedShelter(6, address = "강원도 춘천시 신북읍")
        seedShelter(7, address = "충남 청양군 청양읍")
        seedShelter(8, address = "전라북도 전주시 덕진구")
        seedShelter(9, address = "경기도 광주시 오포읍")
        seedShelter(10, address = "광주광역시 남구 송하동")
        for (shelter in 1L..10L) {
            seedAnimal(100 + shelter, shelter)
        }

        // when
        fun byRegion(region: String, city: String? = null) = ids(discovery.search(search(region = region, city = city), page())).sorted()

        // then
        assertThat(byRegion("광주광역시")).`as`("광주의 구로 시작하는 통합 주소와 옛 주소, 경기도 광주시는 아님").isEqualTo(listOf(103L, 110L))
        assertThat(byRegion("전라남도")).`as`("광주의 구가 아닌 통합 주소와 옛 주소").isEqualTo(listOf(104L, 105L))
        assertThat(byRegion("전남광주통합특별시")).isEqualTo(listOf(103L, 104L, 105L, 110L))
        assertThat(byRegion("강원특별자치도")).isEqualTo(listOf(106L))
        assertThat(byRegion("충청남도")).isEqualTo(listOf(107L))
        assertThat(byRegion("전북특별자치도")).isEqualTo(listOf(108L))
        assertThat(byRegion("경기도")).isEqualTo(listOf(109L))
        assertThat(byRegion("광주광역시", city = "북구")).isEqualTo(listOf(103L))
        assertThat(byRegion("서울특별시")).isEqualTo(listOf(101L))
        assertThat(byRegion("부산광역시")).isEqualTo(listOf(102L))
    }

    @Test
    fun `공고 번호를 줄 때__검색하면__상태 제한 없이 그 번호의 동물을 준다`() {
        // given
        seedAnimal(10, 1, status = "ADOPTED", noticeNo = "NO-ADOPTED")
        seedAnimal(11, 1, status = "NOTICE", noticeNo = "NO-OPEN")

        // when
        val byNumber = discovery.search(search(noticeNo = " NO-ADOPTED "), page())
        val adopted = discovery.search(search(status = AnimalStatus.ADOPTED), page())

        // then
        assertThat(ids(byNumber)).isEqualTo(listOf(10L))
        assertThat(ids(adopted)).isEqualTo(listOf(10L))
    }

    @Test
    fun `정렬과 쪼개기를 줄 때__검색하면__정렬대로 자르고 총계를 세며 나이순은 출생연도를 뒤집는다`() {
        // given
        for (offset in 0 until 5) {
            seedAnimal(10L + offset, 1, noticeEnd = "2026-03-20", birthYear = 2020 + offset, createdAt = "2026-03-0${offset + 1} 00:00:00")
        }

        // when
        val newest = discovery.search(search(), page(0, 2, Order("createdAt", false)))
        val second = discovery.search(search(), page(1, 2, Order("createdAt", false)))
        val youngest = discovery.search(search(), page(0, 5, Order("age", true)))
        val beyond = discovery.search(search(), page(9, 2, Order("createdAt", false)))

        // then
        assertThat(ids(newest)).isEqualTo(listOf(14L, 13L))
        assertThat(newest.totalElements()).isEqualTo(5)
        assertThat(newest.totalPages()).isEqualTo(3)
        assertThat(ids(second)).isEqualTo(listOf(12L, 11L))
        assertThat(ids(youngest)).`as`("Ascending age is the latest birth year first").isEqualTo(listOf(14L, 13L, 12L, 11L, 10L))
        assertThat(beyond.content()).isEmpty()
        assertThat(beyond.totalElements()).isEqualTo(5)
    }

    @Test
    fun `쪽 크기나 정렬이 어긋날 때__검색하면__질의 전에 거부한다`() {
        // given
        // when
        // then
        for (bad in listOf(
            page(0, 101), page(0, 0), page(-1, 5), page(100, 100), page(0, 5, Order("a", true), Order("b", true)),
            page(0, 5, Order("password", true)), page(0, 5, Order("relevance", true)),
        )) {
            assertThatThrownBy { discovery.search(search(), bad) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `검색 문서가 없는 동물이 있을 때__키워드로 검색하면__갱신 중 예외이고 갱신 뒤에는 키워드와 보호소 이름으로 찾는다`() {
        // given
        seedAnimal(10, 1, breed = "골든 리트리버", specialMark = "다리에 흉터가 있음")
        seedAnimal(11, 2, breed = "푸들", specialMark = "순한 성격")
        val projector = PostgresqlSearchProjector(JdbcTemplate(writeSource), TransactionTemplate(writeManager), analyzer)

        // when
        assertThatThrownBy { discovery.search(search(keyword = "리트리버"), page()) }.isInstanceOf(SearchProjectionPendingException::class.java)
        val refreshedShelters = projector.refreshShelters(10)
        val refreshedAnimals = projector.refreshAnimals(10)
        val byBreed = discovery.search(search(keyword = "리트리버"), page())
        val byShelter = discovery.search(search(keyword = "바다"), page())
        val none = discovery.search(search(keyword = "고양이"), page())
        val relevance = discovery.search(search(breed = "푸들"), page(0, 5, Order("relevance", false)))

        // then
        assertThat(refreshedShelters).isEqualTo(2)
        assertThat(refreshedAnimals).isEqualTo(2)
        assertThat(ids(byBreed)).isEqualTo(listOf(10L))
        assertThat(ids(byShelter)).`as`("A shelter name finds its resident animals").isEqualTo(listOf(11L))
        assertThat(none.content()).isEmpty()
        assertThat(ids(relevance)).isEqualTo(listOf(11L))
        assertThat(projector.refreshAnimals(10)).`as`("Audit finds the documents current").isZero()
    }

    @Test
    fun `동물과 APMS 번호가 있을 때__상세를 읽으면__모든 필드로 주고 없으면 없음 예외다`() {
        // given
        seedAnimal(10, 1, desertionNo = "D-10", description = "상세 설명", breed = "푸들", specialMark = "점", apmsUpdatedAt = "2026-03-02 03:04:05")

        // when
        val byId = discovery.findById(10)
        val byNumber = discovery.findByApmsDesertionNo("D-10")

        // then
        assertThat(byNumber).isEqualTo(byId)
        assertThat(byId.apmsDesertionNo).isEqualTo("D-10")
        assertThat(byId.description).isEqualTo("상세 설명")
        assertThat(byId.shelterId).isEqualTo(1)
        assertThat(byId.apmsUpdatedAt.toString()).isEqualTo("2026-03-02T03:04:05")
        assertThatThrownBy { discovery.findById(99) }.isInstanceOf(AnimalNotFoundException::class.java)
        assertThatThrownBy { discovery.findByApmsDesertionNo("none") }.isInstanceOf(AnimalNotFoundException::class.java)
    }

    @Test
    fun `공고 종료가 사흘 안인 보호 중 동물이 있을 때__임박 목록을 읽으면__그 동물만 종료일 순서로 준다`() {
        // given
        val today = LocalDate.now()
        seedAnimal(10, 1, status = "PROTECT", noticeEnd = today.plusDays(3).toString())
        seedAnimal(11, 1, status = "PROTECT", noticeEnd = today.toString())
        seedAnimal(12, 1, status = "PROTECT", noticeEnd = today.plusDays(4).toString())
        seedAnimal(13, 1, status = "PROTECT", noticeEnd = today.minusDays(1).toString())
        seedAnimal(14, 1, status = "NOTICE", noticeEnd = today.plusDays(1).toString())

        // when
        val result = discovery.findExpiringSoonAnimals(page())

        // then
        assertThat(ids(result)).isEqualTo(listOf(11L, 10L))
        assertThat(result.totalElements()).isEqualTo(2)
    }
}
