package com.pawbridge.backend.user.internal.adminupdate.web

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import jakarta.validation.constraints.Pattern

@JvmRecord
data class AdminUserUpdateRequest(
    // 길이는 본인 변경 규칙(UpdateNicknameRequest)과 같은 2~16자다.
    @field:Pattern(regexp = "^[a-zA-Z0-9가-힣]{2,16}\$", message = "닉네임은 2~16자의 영문, 숫자, 한글만 가능합니다.")
    val nickname: String?,
    val role: AdminUserUpdate.Role?,
    val careRegNo: String?,
)
