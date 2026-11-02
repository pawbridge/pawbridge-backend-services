package com.pawbridge.backend.user.internal.animalactivity.web

import com.pawbridge.backend.user.internal.animalactivity.application.FavoriteNotifications
import com.pawbridge.backend.user.internal.profile.web.UserProfileController.Response
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class FavoriteNotificationsController(private val notifications: FavoriteNotifications) {

    // Per-member and changing every sync, so no shared or browser cache may keep it.
    @GetMapping("/api/v1/users/me/favorite-animal-notifications")
    fun recent(@AuthenticationPrincipal current: Long?): ResponseEntity<Response<FavoriteNotifications.Notifications>> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(Response(200, "요청이 성공적으로 처리되었습니다.", notifications.recent(current!!)))
}
