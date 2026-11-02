package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteActivityRejectedException
import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteNotifications
import com.pawbridge.backend.user.internal.animalactivity.application.ShelterMembershipRequiredException
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.animalactivity.web.FavoriteNotificationsController
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesController
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesExceptionHandler
import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.time.Instant
import java.time.LocalDateTime

@WebMvcTest(
    properties = ["pawbridge.user-animal-activities.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        UserAnimalActivitiesController::class,
        FavoriteNotificationsController::class,
        UserAnimalActivitiesExceptionHandler::class,
        FavoriteStatusController::class,
        AdminPostReadConfiguration::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class UserAnimalActivitiesHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var activities: UserAnimalActivities

    @MockkBean
    lateinit var notifications: FavoriteNotifications

    @MockkBean
    lateinit var statusQuery: FavoriteStatusQuery

    @MockkBean
    lateinit var posts: ReadAdminPosts

    @MockkBean
    lateinit var post: ReadPost

    @MockkBean
    lateinit var postPages: PostPageReader

    @Test
    fun `검증된 주체와 위조 헤더가 주어졌을 때__좋아요를 추가하고 제거하면__원래 메시지와 널 필드와 긴 ID를 유지한다`() {
        // given
        every { activities.add(any(), any()) } returns
            UserAnimalActivities.Favorite(Long.MAX_VALUE, 7L, 8L, LocalDateTime.parse("2026-10-20T01:02:03.123456789"))
        justRun { activities.remove(any(), any()) }

        // when
        val added = mvc.perform(post("/api/v1/favorites/8").header("Authorization", token()).header("X-User-Id", "999"))
        val removed = mvc.perform(delete("/api/v1/favorites/8").header("Authorization", token()))

        // then
        added
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"좋아요가 추가되었습니다.\",\"data\":{\"favoriteId\":9223372036854775807,\"userId\":7,\"animalId\":8,\"createdAt\":\"2026-10-20T01:02:03.123456789\"}}",
                ),
            )
            .andExpect(header().doesNotExist("Set-Cookie"))
        removed
            .andExpect(status().isOk())
            .andExpect(content().json("{\"code\":200,\"message\":\"좋아요가 제거되었습니다.\",\"data\":null}"))
        verify(exactly = 1) { activities.add(7, 8) }
        verify(exactly = 1) { activities.remove(7, 8) }
        confirmVerified(activities)
        cleared()
    }

    @Test
    fun `좋아요와 등록 동물 페이지가 주어졌을 때__조회하면__원본 DTO와 기본 또는 다중 정렬 전달을 유지한다`() {
        // given
        every { activities.favorites(any()) } returns UserAnimalActivities.Favorites(7L, 0, listOf())
        every { activities.registered(any(), any()) } returns UserAnimalActivities.AnimalPage(listOf(), 0, 20, 41, 3, false)

        // when
        val favorites = mvc.perform(get("/api/v1/users/me/favorite-animals").header("Authorization", token()))
        val defaultSort = mvc.perform(get("/api/v1/users/me/registered-animals").header("Authorization", token()))
        val multiSort = mvc.perform(
            get("/api/v1/users/me/registered-animals")
                .param("page", "3")
                .param("sort", "name,desc", "createdAt,asc")
                .header("Authorization", token()),
        )

        // then
        favorites
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(0))
        defaultSort
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.totalElements").value(41))
        multiSort.andExpect(status().isOk())
        verify(exactly = 1) { activities.favorites(7) }
        verify(exactly = 1) { activities.registered(7, UserAnimalActivities.PageRequest(0, 20, "createdAt,desc")) }
        verify(exactly = 1) { activities.registered(7, UserAnimalActivities.PageRequest(3, 20, "name,desc,createdAt,asc")) }
        cleared()
    }

    @Test
    fun `관심 동물 상태가 바뀌었을 때__알림을 조회하면__본인 기준으로 변화와 요약을 주고 저장하지 않게 한다`() {
        // given
        every { notifications.recent(any()) } returns FavoriteNotifications.Notifications(
            listOf(
                FavoriteNotifications.Notification(
                    8L, "ADOPTED", "PROTECT", Instant.parse("2026-11-02T03:00:00.123456Z"),
                    "개", "믹스", "https://image.invalid/8", "N-8", "행복 보호소",
                ),
            ),
        )

        // when
        val result = mvc.perform(get("/api/v1/users/me/favorite-animal-notifications").header("Authorization", token()))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"data\":{\"content\":[{\"animalId\":8,\"status\":\"ADOPTED\",\"previousStatus\":\"PROTECT\"," +
                        "\"changedAt\":\"2026-11-02T03:00:00.123456Z\",\"breed\":\"믹스\",\"shelterName\":\"행복 보호소\"}]}}",
                ),
            )
        verify(exactly = 1) { notifications.recent(7) }
        confirmVerified(notifications)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["duplicate", "missing", "animal", "user", "runtime", "commit"])
    fun `업무 또는 저장소 실패가 주어졌을 때__변경하면__원래 응답을 유지하고 내부 예외 문구를 노출하지 않는다`(phase: String) {
        // given
        val failure: RuntimeException = when (phase) {
            "duplicate" -> FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.ALREADY_EXISTS)
            "missing" -> FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.NOT_FOUND)
            "animal" -> FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.ANIMAL_NOT_FOUND)
            "user" -> UserNotFoundException()
            "commit" -> TransactionSystemException("synthetic-private-secret")
            else -> IllegalStateException("synthetic-private-secret")
        }
        if (phase == "missing") {
            every { activities.remove(any(), any()) } throws failure
        } else {
            every { activities.add(any(), any()) } throws failure
        }
        val expectedStatus = if (phase == "duplicate") {
            409
        } else if (phase == "missing" || phase == "animal") {
            404
        } else if (phase == "user") {
            400
        } else {
            500
        }
        val request = if (phase == "missing") delete("/api/v1/favorites/8") else post("/api/v1/favorites/8")

        // when
        val response = mvc.perform(request.header("Authorization", token()))
            .andExpect(status().`is`(expectedStatus))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andReturn().response

        // then
        assertThat(response.contentAsString).doesNotContain("synthetic-private-secret")
        assertThat(response.getHeader("Cache-Control")).contains("no-store")
        if (phase == "missing") {
            verify(exactly = 1) { activities.remove(7, 8) }
        } else {
            verify(exactly = 1) { activities.add(7, 8) }
        }
        confirmVerified(activities)
        cleared()
    }

    @Test
    fun `예상치 못한 실패가 주어졌을 때__등록 동물을 요청하면__일반 500을 유지한다`() {
        // given
        every { activities.registered(any(), any()) } throws IllegalStateException("synthetic-private")

        // when
        val result = mvc.perform(get("/api/v1/users/me/registered-animals").header("Authorization", token()))

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 에러!"))
        verify(exactly = 1) { activities.registered(7, UserAnimalActivities.PageRequest(0, 20, "createdAt,desc")) }
        cleared()
    }

    @Test
    fun `보호소 회원이 아닐 때__등록 동물을 요청하면__403을 응답한다`() {
        // given
        every { activities.registered(any(), any()) } throws ShelterMembershipRequiredException()

        // when
        val result = mvc.perform(get("/api/v1/users/me/registered-animals").header("Authorization", token()))

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("보호소 회원만 등록한 동물을 조회할 수 있습니다."))
        verify(exactly = 1) { activities.registered(7, UserAnimalActivities.PageRequest(0, 20, "createdAt,desc")) }
        cleared()
    }

    companion object {
        private fun token(): String = "Bearer " + F3TestInputs.token(7, "ROLE_USER")

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
