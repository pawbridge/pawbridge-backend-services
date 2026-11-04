package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate

/**
 * multipart/form-data를 `@ModelAttribute`로 바인딩하는 요청이다.
 * Lombok `@Data`처럼 기본 생성자와 public getter/setter를 제공하고 값 기반 equals/hashCode/toString을 가진다.
 */
class LostSearchRequest {
    @field:NotNull
    var image: MultipartFile? = null

    @field:NotNull
    var species: Species? = null

    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    var lostDate: LocalDate? = null

    @field:Size(max = 100)
    var region: String? = null

    @field:Size(max = 500)
    var description: String? = null

    // Lombok이 primitive boolean 필드에 만들던 isIncludeAdoptedOrReturned()/setIncludeAdoptedOrReturned(boolean)과 같은 이름이다.
    // 속성 이름이 `isXxx`이면 Spring의 Kotlin 속성 탐색이 바인딩 이름을 "isXxx"로 보므로, 속성은 includeAdoptedOrReturned로 두고 getter 이름만 바꾼다.
    // 이름과 달리 살아서 보호소를 떠난 동물 전체(입양, 반환, 기증, 방사)를 뜻한다. 프론트·네이티브와의 호환을 위해 이름은 그대로 둔다.
    @get:JvmName("isIncludeAdoptedOrReturned")
    var includeAdoptedOrReturned: Boolean = false

    // 보호 중 사망한 기록(자연사, 안락사)을 살아 있는 후보와 따로 받는다.
    @get:JvmName("isIncludeDeceased")
    var includeDeceased: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        if (other !is LostSearchRequest) {
            return false
        }
        // Lombok compares and hashes the primitive field first.
        return includeAdoptedOrReturned == other.includeAdoptedOrReturned &&
            includeDeceased == other.includeDeceased &&
            image == other.image &&
            species == other.species &&
            lostDate == other.lostDate &&
            region == other.region &&
            description == other.description
    }

    override fun hashCode(): Int {
        val prime = 59
        var result = 1
        result = result * prime + (if (includeAdoptedOrReturned) 79 else 97)
        result = result * prime + (if (includeDeceased) 79 else 97)
        result = result * prime + (image?.hashCode() ?: 43)
        result = result * prime + (species?.hashCode() ?: 43)
        result = result * prime + (lostDate?.hashCode() ?: 43)
        result = result * prime + (region?.hashCode() ?: 43)
        result = result * prime + (description?.hashCode() ?: 43)
        return result
    }

    override fun toString(): String {
        return "LostSearchRequest(image=" + image +
            ", species=" + species +
            ", lostDate=" + lostDate +
            ", region=" + region +
            ", description=" + description +
            ", includeAdoptedOrReturned=" + includeAdoptedOrReturned +
            ", includeDeceased=" + includeDeceased + ")"
    }
}
