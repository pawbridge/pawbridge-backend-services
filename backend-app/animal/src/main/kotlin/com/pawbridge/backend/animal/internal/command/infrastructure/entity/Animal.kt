package com.pawbridge.backend.animal.internal.command.infrastructure.entity

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Gender
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.NeuterStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year

/**
 * 유기동물 정보 엔티티
 * - APMS(동물보호관리시스템) API 응답 데이터를 기반으로 설계
 * - 실제 API 응답에서 자주 채워지는 필드만 포함 (27개)
 */
@Entity
@Table(
    name = "animals",
    schema = "pawbridge_animal",
    indexes = [
        Index(name = "idx_apms_desertion_no", columnList = "apmsDesertionNo", unique = true),
        Index(name = "idx_apms_notice_no", columnList = "apmsNoticeNo"),
        Index(name = "idx_species_status", columnList = "species,status"),
        Index(name = "idx_notice_end_date", columnList = "noticeEndDate"),
        Index(name = "idx_shelter_id", columnList = "shelter_id"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class Animal private constructor(
    id: Long?,
    apmsDesertionNo: String?,
    apmsNoticeNo: String?,
    species: Species?,
    breed: String?,
    birthYear: Int?,
    weight: String?,
    color: String?,
    gender: Gender?,
    neuterStatus: NeuterStatus?,
    specialMark: String?,
    apmsProcessState: String?,
    noticeStartDate: LocalDate?,
    noticeEndDate: LocalDate?,
    apmsUpdatedAt: LocalDateTime?,
    happenDate: LocalDate?,
    happenPlace: String?,
    imageUrl: String?,
    imageUrl2: String?,
    shelter: Shelter?,
    status: AnimalStatus?,
    apiSource: ApiSource?,
    favoriteCount: Int?,
    description: String?,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set

    // APMS 핵심 식별 (2개)
    /**
     * APMS 유기번호 (desertionNo)
     * - 예: "448567202501701"
     * - UNIQUE 제약: 중복 체크용
     * - APMS 배치 전용 (수동 등록 시 null)
     */
    @Column(unique = true, length = 50)
    var apmsDesertionNo: String? = apmsDesertionNo
        protected set

    /**
     * 공고번호 (noticeNo)
     * - 예: "경남-창원1-2025-00833", "경기-양평-2025-00429"
     * - APMS 배치 + 수동 등록 모두 사용
     * - 서로 다른 유기번호에 같은 공고번호가 부여될 수 있으므로 고유 식별자가 아님
     * - 동물 식별과 중복 방지는 id / apmsDesertionNo를 사용
     */
    @Column(nullable = false, length = 100)
    var apmsNoticeNo: String? = apmsNoticeNo
        protected set

    // 동물 기본 정보 (9개)
    /**
     * 축종 (upKindCd)
     * - 417000 → DOG, 422400 → CAT, 429900 → ETC
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var species: Species? = species
        protected set

    /**
     * 품종명 (kindNm)
     * - 예: "믹스견", "라브라도 리트리버"
     */
    @Column(length = 100)
    var breed: String? = breed
        protected set

    /**
     * 출생 연도
     * - age 필드에서 추출: "2023(년생)" → 2023
     * - "2025(60일미만)(년생)" → 2025
     */
    var birthYear: Int? = birthYear
        protected set

    /**
     * 체중 (weight)
     * - 예: "12(Kg)", "2.2(Kg)"
     * - String으로 저장 (단위 포함)
     */
    @Column(length = 50)
    var weight: String? = weight
        protected set

    /**
     * 색상 (colorCd)
     * - 예: "옅은 황색", "검정&흰색", "갈색"
     */
    @Column(length = 100)
    var color: String? = color
        protected set

    /**
     * 성별 (sexCd)
     * - M → MALE, F → FEMALE, Q → UNKNOWN
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var gender: Gender? = gender
        protected set

    /**
     * 중성화 여부 (neuterYn)
     * - Y → YES, N → NO, U → UNKNOWN
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var neuterStatus: NeuterStatus? = neuterStatus
        protected set

    /**
     * 특징 (specialMark)
     * - 예: "엄청 까불까불하고 순해요~잘생김~보호자분도 애타게 찾으실듯..."
     */
    @Column(length = 1000)
    var specialMark: String? = specialMark
        protected set

    // APMS 공고 정보 (4개)
    /**
     * APMS 진행상태 (processState)
     * - 예: "공고중", "보호중"
     * - APMS의 원본 상태 (자체 status와 분리)
     * - APMS 배치 전용
     */
    @Column(length = 50)
    var apmsProcessState: String? = apmsProcessState
        protected set

    /**
     * 공고 시작일 (noticeSdt)
     * - YYYYMMDD → LocalDate 변환
     * - APMS 배치 + 수동 등록 모두 사용 (필수)
     */
    @Column(nullable = false)
    var noticeStartDate: LocalDate? = noticeStartDate
        protected set

    /**
     * 공고 종료일 (noticeEdt)
     * - YYYYMMDD → LocalDate 변환
     * - 공고 종료 임박 정렬에 사용
     * - APMS 배치 + 수동 등록 모두 사용 (필수)
     */
    @Column(nullable = false)
    var noticeEndDate: LocalDate? = noticeEndDate
        protected set

    /**
     * APMS 마지막 수정 시각 (updTm)
     * - 예: "2025-11-09 17:34:36.0"
     * - 동기화 시 변경 감지에 유용
     */
    var apmsUpdatedAt: LocalDateTime? = apmsUpdatedAt
        protected set

    // 발견 정보 (2개)
    /**
     * 접수일 (happenDt)
     * - YYYYMMDD → LocalDate 변환
     */
    var happenDate: LocalDate? = happenDate
        protected set

    /**
     * 발견 장소 (happenPlace)
     * - 예: "진해구 남문동 남문시티 1차"
     */
    @Column(length = 200)
    var happenPlace: String? = happenPlace
        protected set

    // 이미지 (2개)
    /**
     * 대표 이미지 (popfile1)
     * - URL 형태
     */
    @Column(length = 500)
    var imageUrl: String? = imageUrl
        protected set

    /**
     * 추가 이미지 (popfile2)
     * - 실제 응답에서 거의 항상 포함됨
     */
    @Column(length = 500)
    var imageUrl2: String? = imageUrl2
        protected set

    /**
     * 보호소 FK
     * - careRegNo로 Shelter 조회 후 연결
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shelter_id", nullable = false)
    var shelter: Shelter? = shelter
        protected set

    // 자체 관리 필드 (4개)
    /**
     * 자체 관리 상태 (AnimalStatus)
     * - NOTICE, PROTECT, ADOPTION_PENDING, ADOPTED 등
     * - apmsProcessState와 분리 관리
     * - 예: APMS는 "공고중"이지만 우리 시스템에서는 "입양완료"
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: AnimalStatus? = status
        protected set

    /** 마지막 상태 변경 직전의 상태(관심 동물 알림용). 변경 기록이 없으면 null이다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    var previousStatus: AnimalStatus? = null
        protected set

    /** 마지막으로 상태가 바뀐 시각(관심 동물 알림용). 변경 기록이 없으면 null이다. */
    @Column
    var statusChangedAt: Instant? = null
        protected set

    /**
     * API 출처 (ApiSource)
     * - APMS_ANIMAL, GYEONGGI, MANUAL 등
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var apiSource: ApiSource? = apiSource
        protected set

    /**
     * 찜 횟수 (캐시)
     * - user-service의 favorite 이벤트로 업데이트
     */
    @Column(nullable = false)
    var favoriteCount: Int? = favoriteCount
        protected set

    /**
     * 보호소가 추가 작성한 설명 (자체)
     * - APMS 데이터 외 보호소가 직접 입력
     */
    @Column(length = 2000)
    var description: String? = description
        protected set

    // Hibernate와 Java 호출부가 쓰는 기본 생성자. Lombok의 @Builder.Default처럼 favoriteCount는 0으로 시작한다.
    protected constructor() : this(
        id = null,
        apmsDesertionNo = null,
        apmsNoticeNo = null,
        species = null,
        breed = null,
        birthYear = null,
        weight = null,
        color = null,
        gender = null,
        neuterStatus = null,
        specialMark = null,
        apmsProcessState = null,
        noticeStartDate = null,
        noticeEndDate = null,
        apmsUpdatedAt = null,
        happenDate = null,
        happenPlace = null,
        imageUrl = null,
        imageUrl2 = null,
        shelter = null,
        status = null,
        apiSource = null,
        favoriteCount = 0,
        description = null,
    )

    // 비즈니스 메서드
    /**
     * 상태 변경
     * @param newStatus 새로운 상태
     */
    fun updateStatus(newStatus: AnimalStatus?, at: Instant) {
        AnimalStatusChange.between(status, newStatus, at)?.let { change ->
            previousStatus = change.previousStatus
            statusChangedAt = change.changedAt
        }
        this.status = newStatus
    }

    /**
     * 설명 수정
     * @param description 새로운 설명
     */
    fun updateDescription(description: String?) {
        this.description = description
    }

    /**
     * 보호소가 직접 등록한 동물의 정보 수정
     * - 요청 값으로 통째로 바꾼다 (null이면 지움)
     * - 상태, 출처, 공고번호, 보호소, 찜 횟수, APMS 필드는 유지한다
     */
    fun updateManualDetails(
        noticeStartDate: LocalDate?,
        noticeEndDate: LocalDate?,
        species: Species?,
        gender: Gender?,
        neuterStatus: NeuterStatus?,
        breed: String?,
        birthYear: Int?,
        weight: String?,
        color: String?,
        specialMark: String?,
        happenDate: LocalDate?,
        happenPlace: String?,
        imageUrl: String?,
        imageUrl2: String?,
        description: String?,
    ) {
        this.noticeStartDate = noticeStartDate
        this.noticeEndDate = noticeEndDate
        this.species = species
        this.gender = gender
        this.neuterStatus = neuterStatus
        this.breed = breed
        this.birthYear = birthYear
        this.weight = weight
        this.color = color
        this.specialMark = specialMark
        this.happenDate = happenDate
        this.happenPlace = happenPlace
        this.imageUrl = imageUrl
        this.imageUrl2 = imageUrl2
        this.description = description
    }

    /**
     * 찜 횟수 증가
     */
    fun incrementFavoriteCount() {
        // Java의 Integer 언박싱처럼 favoriteCount가 null이면 NullPointerException이다.
        this.favoriteCount = this.favoriteCount!! + 1
    }

    /**
     * 찜 횟수 감소
     */
    fun decrementFavoriteCount() {
        val current = this.favoriteCount!!
        if (current > 0) {
            this.favoriteCount = current - 1
        }
    }

    /**
     * 나이 계산 (현재 연도 기준)
     * @return 만 나이
     */
    fun getAge(): Int? {
        val birthYear = this.birthYear ?: return null
        return Year.now().value - birthYear
    }

    /**
     * 공고 종료 임박 여부
     * @return 3일 이내 종료 시 true
     */
    fun isNoticeExpiringSoon(): Boolean {
        val noticeEndDate = this.noticeEndDate ?: return false
        return LocalDate.now().plusDays(3).isAfter(noticeEndDate)
    }

    // 연관관계 메서드
    /**
     * Shelter 설정
     * @param shelter 보호소
     */
    internal fun assignShelter(shelter: Shelter?) {
        this.shelter = shelter
    }

    class AnimalBuilder internal constructor() {
        private var id: Long? = null
        private var apmsDesertionNo: String? = null
        private var apmsNoticeNo: String? = null
        private var species: Species? = null
        private var breed: String? = null
        private var birthYear: Int? = null
        private var weight: String? = null
        private var color: String? = null
        private var gender: Gender? = null
        private var neuterStatus: NeuterStatus? = null
        private var specialMark: String? = null
        private var apmsProcessState: String? = null
        private var noticeStartDate: LocalDate? = null
        private var noticeEndDate: LocalDate? = null
        private var apmsUpdatedAt: LocalDateTime? = null
        private var happenDate: LocalDate? = null
        private var happenPlace: String? = null
        private var imageUrl: String? = null
        private var imageUrl2: String? = null
        private var shelter: Shelter? = null
        private var status: AnimalStatus? = null
        private var apiSource: ApiSource? = null
        private var favoriteCount: Int? = 0
        private var description: String? = null

        fun id(id: Long?): AnimalBuilder = apply {
            this.id = id
        }

        fun apmsDesertionNo(apmsDesertionNo: String?): AnimalBuilder = apply {
            this.apmsDesertionNo = apmsDesertionNo
        }

        fun apmsNoticeNo(apmsNoticeNo: String?): AnimalBuilder = apply {
            this.apmsNoticeNo = apmsNoticeNo
        }

        fun species(species: Species?): AnimalBuilder = apply {
            this.species = species
        }

        fun breed(breed: String?): AnimalBuilder = apply {
            this.breed = breed
        }

        fun birthYear(birthYear: Int?): AnimalBuilder = apply {
            this.birthYear = birthYear
        }

        fun weight(weight: String?): AnimalBuilder = apply {
            this.weight = weight
        }

        fun color(color: String?): AnimalBuilder = apply {
            this.color = color
        }

        fun gender(gender: Gender?): AnimalBuilder = apply {
            this.gender = gender
        }

        fun neuterStatus(neuterStatus: NeuterStatus?): AnimalBuilder = apply {
            this.neuterStatus = neuterStatus
        }

        fun specialMark(specialMark: String?): AnimalBuilder = apply {
            this.specialMark = specialMark
        }

        fun apmsProcessState(apmsProcessState: String?): AnimalBuilder = apply {
            this.apmsProcessState = apmsProcessState
        }

        fun noticeStartDate(noticeStartDate: LocalDate?): AnimalBuilder = apply {
            this.noticeStartDate = noticeStartDate
        }

        fun noticeEndDate(noticeEndDate: LocalDate?): AnimalBuilder = apply {
            this.noticeEndDate = noticeEndDate
        }

        fun apmsUpdatedAt(apmsUpdatedAt: LocalDateTime?): AnimalBuilder = apply {
            this.apmsUpdatedAt = apmsUpdatedAt
        }

        fun happenDate(happenDate: LocalDate?): AnimalBuilder = apply {
            this.happenDate = happenDate
        }

        fun happenPlace(happenPlace: String?): AnimalBuilder = apply {
            this.happenPlace = happenPlace
        }

        fun imageUrl(imageUrl: String?): AnimalBuilder = apply {
            this.imageUrl = imageUrl
        }

        fun imageUrl2(imageUrl2: String?): AnimalBuilder = apply {
            this.imageUrl2 = imageUrl2
        }

        fun shelter(shelter: Shelter?): AnimalBuilder = apply {
            this.shelter = shelter
        }

        fun status(status: AnimalStatus?): AnimalBuilder = apply {
            this.status = status
        }

        fun apiSource(apiSource: ApiSource?): AnimalBuilder = apply {
            this.apiSource = apiSource
        }

        fun favoriteCount(favoriteCount: Int?): AnimalBuilder = apply {
            this.favoriteCount = favoriteCount
        }

        fun description(description: String?): AnimalBuilder = apply {
            this.description = description
        }

        fun build(): Animal = Animal(
            id,
            apmsDesertionNo,
            apmsNoticeNo,
            species,
            breed,
            birthYear,
            weight,
            color,
            gender,
            neuterStatus,
            specialMark,
            apmsProcessState,
            noticeStartDate,
            noticeEndDate,
            apmsUpdatedAt,
            happenDate,
            happenPlace,
            imageUrl,
            imageUrl2,
            shelter,
            status,
            apiSource,
            favoriteCount,
            description,
        )
    }

    companion object {
        @JvmStatic
        fun builder(): AnimalBuilder = AnimalBuilder()
    }
}
