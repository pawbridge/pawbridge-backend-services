package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Changes
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Role
import com.pawbridge.backend.user.internal.adminupdate.application.ShelterApprovalRequiredException
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.util.stream.Stream

@WebMvcTest(
    controllers = [AdminUserUpdateController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AdminUserUpdateController::class,
        AdminUserUpdateExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AdminUserUpdateHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var members: AdminUserUpdate

    @Test
    fun `서명된 관리자와 숫자 닉네임__다른 대상을 수정하면__경로와 원래 필드만 바인딩하고 원래 봉투를 돌려준다`() {
        // given
        justRun { members.update(any(), any()) }

        // when
        val result = mvc.perform(
            put(PATH + Long.MAX_VALUE)
                .header("Authorization", admin())
                .header("X-User-Id", "9")
                .header("X-User-Role", "ROLE_USER")
                .param("userId", "9")
                .contentType("application/json")
                .content("{\"nickname\":\"123\",\"role\":\"ROLE_ADMIN\",\"careRegNo\":\"A\",\"userId\":9}"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":200,\"message\":\"회원 정보가 수정되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { members.update(Long.MAX_VALUE, Changes("123", Role.ROLE_ADMIN, "A")) }
        confirmVerified(members)
        cleared()
    }

    @Test
    fun `null 수정 필드__관리자가 수정하면__새 필수 필드 없이 원본의 선택 요청을 유지한다`() {
        // given
        justRun { members.update(any(), any()) }

        // when
        val result = mvc.perform(put(PATH + 0).header("Authorization", admin()).contentType("application/json").content("{}"))

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) { members.update(0, Changes(null, null, null)) }
        confirmVerified(members)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["가1", "굉장히사랑스러운기니피그9999"])
    fun `두 글자 또는 열여섯 글자 닉네임__관리자가 수정하면__원문을 그대로 전달한다`(nickname: String) {
        // given
        justRun { members.update(any(), any()) }
        val json = ObjectMapper().writeValueAsString(mapOf("nickname" to nickname))

        // when
        val result = mvc.perform(put(PATH + 8).header("Authorization", admin()).contentType("application/json").content(json))

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) { members.update(8, Changes(nickname, null, null)) }
        confirmVerified(members)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "x", "12345678901234567", "emoji🐾"])
    fun `관리자 전용 패턴을 벗어난 닉네임__검증하면__업무 처리 전에 원래 400을 돌려준다`(
        nickname: String,
    ) {
        // given
        val json = ObjectMapper().writeValueAsString(mapOf("nickname" to nickname))

        // when
        val result = mvc.perform(put(PATH + 8).header("Authorization", admin()).contentType("application/json").content(json))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"nickname: 닉네임은 2~16자의 영문, 숫자, 한글만 가능합니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(members)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["{", "{\"role\":\"role_user\"}", "{\"role\":\"ROLE_UNKNOWN\"}"])
    fun `깨진 JSON 또는 알 수 없는 역할__본문을 읽으면__원래 읽을 수 없음 400을 돌려준다`(body: String) {
        // given
        val request = put(PATH + 8).header("Authorization", admin()).contentType("application/json").content(body)

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"요청 본문 형식이 올바르지 않습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(members)
        cleared()
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `승인 누락 중복 DB 또는 커밋 실패__처리하면__원래 상태를 유지하고 내부 정보를 숨긴다`(
        failure: RuntimeException,
        code: Int,
        message: String,
    ) {
        // given
        every { members.update(any(), any()) } throws failure

        // when
        val result = mvc.perform(put(PATH + 8).header("Authorization", admin()).contentType("application/json").content("{}"))

        // then
        result
            .andExpect(status().`is`(code))
            .andExpect(
                content().json(
                    ObjectMapper().writeValueAsString(UserNicknameController.Response<Void?>(code, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { members.update(8, Changes(null, null, null)) }
        confirmVerified(members)
        cleared()
    }

    companion object {
        private const val PATH = "/api/v1/admin/users/"

        @JvmStatic
        fun failures(): Stream<Arguments> {
            val charset = PSQLException(
                ServerErrorMessage(
                    "SERROR\u0000C23514\u0000spawbridge_user\u0000nck_rollback_charset_users_nickname\u0000Msynthetic-private\u0000",
                ),
            )
            return Stream.of(
                Arguments.of(UserNotFoundException(), 400, "존재하지 않는 회원입니다."),
                Arguments.of(ShelterApprovalRequiredException(), 400, "보호소 권한은 담당자 신청 승인으로 부여해야 합니다."),
                Arguments.of(NicknameDuplicateException(), 409, "이미 사용 중인 닉네임입니다."),
                Arguments.of(DataIntegrityViolationException("synthetic unique race"), 500, "서버 에러!"),
                Arguments.of(TransactionSystemException("synthetic-private-commit"), 500, "서버 에러!"),
                Arguments.of(
                    DataIntegrityViolationException("synthetic wrapper", charset),
                    400,
                    "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.",
                ),
            )
        }

        private fun admin(): String = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
