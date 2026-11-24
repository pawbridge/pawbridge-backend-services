package com.pawbridge.backend.user.internal.nickname.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

// A real record, as in Java: Jackson then uses the canonical constructor and rejects duplicate keys the same way.
@JvmRecord
data class UpdateNicknameRequest(
    @field:NotBlank(message = "닉네임은 필수입니다.")
    // 소셜 가입 때 자동으로 만드는 닉네임이 가장 길면 16자다(SocialNicknameGenerator).
    @field:Size(min = 2, max = 16, message = "닉네임은 2~16자여야 합니다.")
    @field:Pattern(
        regexp = "^(?=.*[가-힣a-zA-Z])[가-힣a-zA-Z0-9]+\$",
        message = "닉네임은 한글, 영문, 숫자를 사용할 수 있으며 띄워쓰기가 불가능합니다. " +
            "한글 또는 영문을 최소 1자 포함해야 합니다.",
    )
    val nickname: String?,
)
