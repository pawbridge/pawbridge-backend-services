package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.stream.Stream

@WebMvcTest(
    controllers = [UserNicknameController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        UserNicknameController::class,
        UserNicknameExceptionHandler::class,
        UserProfileController::class,
        UserProfileExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class UserNicknameHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var change: ChangeUserNickname

    @MockkBean
    lateinit var profiles: UserProfileQuery

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `서명된 주체와 위조된 신원이 주어졌을 때__닉네임을 바꾸면__주체만 쓰고 널 성공을 돌려준다`(role: String) {
        // given
        justRun { change.change(any(), any()) }

        // when
        val result = mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"새닉네임7\",\"userId\":999}"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":200,\"message\":\"닉네임이 변경되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { change.change(7, "새닉네임7") }
        confirmVerified(change, profiles, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["가1", "굉장히사랑스러운기니피그9999"])
    fun `두 글자 또는 열여섯 글자 닉네임이 주어졌을 때__바꾸면__원문을 그대로 전달한다`(nickname: String) {
        // given
        justRun { change.change(any(), any()) }

        // when
        val result = mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("nickname" to nickname))),
        )

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) { change.change(7, nickname) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `JSON 불리언 닉네임이 주어졌을 때__바꾸면__기존 Jackson 문자열 변환을 유지한다`(nickname: Boolean) {
        // given
        justRun { change.change(any(), any()) }

        // when
        val result = mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":$nickname}"),
        )

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) { change.change(7, nickname.toString()) }
    }

    @ParameterizedTest
    @MethodSource("invalidNicknames")
    fun `잘못되었거나 없는 닉네임이 주어졌을 때__바꾸면__서비스 전에 검증하고 기존 메시지 집합을 유지한다`(
        json: String,
        messages: List<String>,
    ) {
        // given
        val request = put("/api/v1/users/me/nickname")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)

        // when
        val result = mvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andReturn()

        // then
        val response = mapper.readTree(result.response.contentAsString)
        val message = response.get("message").asText()
        assertThat(message).startsWith("nickname: ")
        assertThat(message.substring("nickname: ".length).split(", nickname: "))
            .containsExactlyInAnyOrderElementsOf(messages)
        assertThat(response.get("data").isNull).isTrue()
        confirmVerified(change, profiles, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "{", "null", "[]", "{\"nickname\":[]}", "{\"nickname\":{}}"])
    fun `깨졌거나 객체가 아닌 JSON이 주어졌을 때__바꾸면__서비스 전에 기존 안전 본문 오류를 돌려준다`(json: String) {
        // given
        val request = put("/api/v1/users/me/nickname")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)

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
        confirmVerified(change, profiles, readPost, listPosts)
    }

    @Test
    fun `인증된 회원이 더는 없을 때__바꾸면__기존 회원 없음 응답을 유지한다`() {
        // given
        every { change.change(any(), any()) } throws UserNotFoundException()

        // when
        val result = mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"새닉네임\"}"),
        )

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"존재하지 않는 회원입니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { change.change(7, "새닉네임") }
    }

    @Test
    fun `닉네임이 이미 사용 중일 때__바꾸면__성공 대체 없이 기존 충돌 응답을 돌려준다`() {
        // given
        every { change.change(any(), any()) } throws NicknameDuplicateException()

        // when
        val result = mvc.perform(
            put("/api/v1/users/me/nickname")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"기존닉네임\"}"),
        )

        // then
        result
            .andExpect(status().isConflict())
            .andExpect(
                content().json(
                    "{\"code\":409,\"message\":\"이미 사용 중인 닉네임입니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { change.change(7, "기존닉네임") }
    }

    companion object {
        private const val REQUIRED = "닉네임은 필수입니다."
        private const val SIZE = "닉네임은 2~16자여야 합니다."
        private const val PATTERN =
            "닉네임은 한글, 영문, 숫자를 사용할 수 있으며 띄워쓰기가 불가능합니다. 한글 또는 영문을 최소 1자 포함해야 합니다."

        @JvmStatic
        fun invalidNicknames(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("{}", listOf(REQUIRED)),
                Arguments.of("{\"nickname\":null}", listOf(REQUIRED)),
                Arguments.of("{\"nickname\":\"\"}", listOf(REQUIRED, SIZE, PATTERN)),
                Arguments.of("{\"nickname\":\"A\"}", listOf(SIZE)),
                Arguments.of("{\"nickname\":\"" + "A".repeat(17) + "\"}", listOf(SIZE)),
                Arguments.of("{\"nickname\":\"닉 이름\"}", listOf(PATTERN)),
                Arguments.of("{\"nickname\":\"닉😀\"}", listOf(PATTERN)),
                Arguments.of("{\"nickname\":42}", listOf(PATTERN)),
            )
        }

        @JvmStatic
        fun rejectedAuthorizations(): Stream<Arguments> {
            val expired = Jwts.builder()
                .claim("userId", 7)
                .claim("role", "ROLE_USER")
                .expiration(Date(1))
                .signWith(F3TestInputs.KEY)
                .compact()
            val otherKey = Keys.hmacShaKeyFor(
                "f13-synthetic-other-signing-key-0123456789012345678901234567890123456789"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            val forged = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER").signWith(otherKey).compact()
            val refresh = Jwts.builder()
                .expiration(Date(System.currentTimeMillis() + 3_600_000))
                .signWith(F3TestInputs.KEY)
                .compact()
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Basic ignored", "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $expired", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $forged", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer $refresh", "토큰 파싱에 실패했습니다."),
            )
        }
    }
}
