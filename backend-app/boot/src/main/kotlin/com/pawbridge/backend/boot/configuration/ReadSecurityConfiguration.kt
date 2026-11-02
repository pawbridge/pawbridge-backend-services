package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.security.GatewayCors
import com.pawbridge.backend.boot.security.PostBearerAuthenticationFilter
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.util.matcher.AndRequestMatcher
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.NegatedRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RegexRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.CorsFilter

@Configuration(proxyBeanMethods = false)
class ReadSecurityConfiguration {
    @Bean
    @Throws(Exception::class)
    fun postReadSecurity(
        http: HttpSecurity,
        tokens: PostTokenAuthenticator,
        @Value("\${pawbridge.visits.enabled:false}") visitsEnabled: Boolean,
        @Value("\${pawbridge.visits.collection-enabled:false}") collectionEnabled: Boolean,
        @Value("\${pawbridge.auth.sessions-enabled:false}") sessionsEnabled: Boolean,
        @Value("\${pawbridge.chat.enabled:\${MEMBER_CHAT_ENABLED:false}}") chatEnabledFlag: Boolean,
        @Value("\${pawbridge.shelter-membership.enabled:false}") shelterMembershipEnabled: Boolean,
        @Value("\${pawbridge.user-animal-activities.enabled:false}") animalActivitiesEnabled: Boolean,
        @Value("\${pawbridge.home-videos.management-enabled:false}") videoManagementEnabled: Boolean,
        @Value("\${pawbridge.animal-reports.enabled:false}") reportsEnabledFlag: Boolean,
        @Value("\${pawbridge.user-store-mypage.enabled:false}") storeMyPageEnabledFlag: Boolean,
        @Value("\${pawbridge.animal-discovery.enabled:false}") animalDiscoveryEnabledFlag: Boolean,
        @Value("\${pawbridge.animal-discovery.enabled:false}") animalExtraDiscoveryFlag: String,
        @Value("\${pawbridge.animal-recommendations.enabled:false}") animalRecommendationsEnabledFlag: Boolean,
        @Value("\${pawbridge.store-catalog.enabled:false}") storeCatalogEnabledFlag: Boolean,
        @Value("\${pawbridge.store-wishlist.enabled:false}") storeWishlistEnabledFlag: Boolean,
        @Value("\${pawbridge.animal-shelters.enabled:false}") animalSheltersEnabledFlag: Boolean,
        @Value("\${pawbridge.animal-query.backend:}") animalQueryBackend: String,
        @Value("\${pawbridge.animal-statistics.enabled:false}") animalStatisticsEnabledFlag: Boolean,
        @Value("\${pawbridge.animal-commands.enabled:false}") animalCommandsEnabledFlag: String,
        @Value("\${pawbridge.shelter-commands.enabled:false}") shelterCommandsFlag: String,
        @Value("\${pawbridge.store-direct-orders.enabled:false}") storeDirectOrdersEnabledFlag: String,
        @Value("\${pawbridge.animal-lost-search.enabled:false}") animalLostSearchEnabledFlag: String,
        @Value("\${pawbridge.store-carts.enabled:false}") storeCartsEnabledFlag: String,
        @Value("\${pawbridge.store-order-administration.enabled:false}") storeOrderAdministrationFlag: String,
        @Value("\${pawbridge.animal-travel.enabled:false}") animalTravelFlag: String,
        @Value("\${lost-gallery-feed.enabled:false}") galleryFeedFlag: String,
        @Value("\${pawbridge.animal-chatbot.enabled:false}") chatbotFlag: String,
        @Value("\${pawbridge.store-catalog-management.enabled:false}") catalogManagementFlag: String,
        @Value("\${pawbridge.payments.enabled:false}") paymentsEnabledFlag: String,
        @Value("\${pawbridge.store-mock-payment.enabled:false}") storeMockPaymentFlag: String,
        @Value("\${pawbridge.animal-images.enabled:false}") animalImagesFlag: String,
        @Value("\${pawbridge.apms-manual.enabled:false}") apmsManualFlag: String,
        @Value(GatewayCors.ORIGIN_PATTERNS_VALUE) corsOriginPatterns: String,
    ): SecurityFilterChain {
        val animalImagesEnabled = RequestMatcher { "true".equals(animalImagesFlag, ignoreCase = true) }
        val animalImageWriting = AndRequestMatcher(animalImagesEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/animals/images", "POST"),
                AntPathRequestMatcher("/api/v1/animals/images/multiple", "POST")))
        val catalogManagementEnabled = RequestMatcher { "true".equals(catalogManagementFlag, ignoreCase = true) }
        val catalogManagementWriting = AndRequestMatcher(catalogManagementEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/products", "POST"),
                AntPathRequestMatcher("/api/v1/products/{productId}", "PATCH"),
                AntPathRequestMatcher("/api/v1/products/{productId}", "DELETE"),
                AntPathRequestMatcher("/api/v1/images", "POST"),
                AntPathRequestMatcher("/api/v1/option-groups", "POST"),
                AntPathRequestMatcher("/api/v1/option-groups/{groupId}", "PUT"),
                AntPathRequestMatcher("/api/v1/option-groups/{groupId}", "DELETE"),
                AntPathRequestMatcher("/api/v1/option-groups/{groupId}/values", "POST"),
                AntPathRequestMatcher("/api/v1/option-groups/values/{valueId}", "PUT"),
                AntPathRequestMatcher("/api/v1/option-groups/values/{valueId}", "DELETE"),
                AntPathRequestMatcher("/api/v1/categories", "POST"),
                AntPathRequestMatcher("/api/v1/categories/{categoryId}", "PUT"),
                AntPathRequestMatcher("/api/v1/categories/{categoryId}", "DELETE")))
        val paymentEnabled = RequestMatcher { "true".equals(paymentsEnabledFlag, ignoreCase = true) }
        val paymentConfirmation = AndRequestMatcher(paymentEnabled, AntPathRequestMatcher("/api/v1/payments/confirm", "POST"))
        // Original Gateway required only a valid token for /api/orders/**; the mock route has no owner check.
        val storeMockPaymentEnabled = RequestMatcher { "true".equals(storeMockPaymentFlag, ignoreCase = true) }
        val storeMockPayment = AndRequestMatcher(storeMockPaymentEnabled,
                AntPathRequestMatcher("/api/v1/orders/{orderId}/payment", "POST"))
        val chatbotEnabled = RequestMatcher { "true".equals(chatbotFlag, ignoreCase = true) && "postgresql" == animalQueryBackend }
        val chatbotMessages = AndRequestMatcher(chatbotEnabled,
                AntPathRequestMatcher("/api/v1/animals/{animalId}/chat/messages", "POST"))
        val galleryFeedEnabled = RequestMatcher { "true".equals(galleryFeedFlag, ignoreCase = true) }
        val galleryFeedRoutes = AndRequestMatcher(galleryFeedEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/internal/animals/lost-gallery", "GET"),
                AntPathRequestMatcher("/internal/animals/lost-gallery/snapshots", "POST"),
                AntPathRequestMatcher("/internal/animals/lost-gallery/snapshots/{id}", "GET"),
                AntPathRequestMatcher("/internal/animals/lost-gallery/snapshots/{id}/pages", "GET"),
                AntPathRequestMatcher("/internal/animals/lost-gallery/snapshots/{id}", "DELETE")))
        val storeOrderAdministrationEnabled = RequestMatcher { "true".equals(storeOrderAdministrationFlag, ignoreCase = true) }
        val storeOrderAdministrationRoutes = AndRequestMatcher(storeOrderAdministrationEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/admin/orders", "GET"),
                AntPathRequestMatcher("/api/v1/admin/orders/{orderId}", "GET"),
                AntPathRequestMatcher("/api/v1/admin/orders/{orderId}/status", "PATCH"),
                AntPathRequestMatcher("/api/v1/admin/orders/{orderId}/delivery-status", "PATCH")))
        val animalTravelEnabled = RequestMatcher {
            "true".equals(animalTravelFlag, ignoreCase = true) &&
                "postgresql".equals(animalQueryBackend, ignoreCase = true)
        }
        val animalTravelReading = AndRequestMatcher(animalTravelEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/places/regions", "GET"),
                AntPathRequestMatcher("/api/v1/places", "GET"),
                AntPathRequestMatcher("/api/v1/places/{contentId}", "GET")))
        val shelterCommandsEnabled = RequestMatcher { "true".equals(shelterCommandsFlag, ignoreCase = true) }
        val shelterCommandAdmin = AndRequestMatcher(shelterCommandsEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/shelters", "POST"),
                AntPathRequestMatcher("/api/v1/shelters/{id}", "DELETE")))
        val shelterCommandUpdate = AndRequestMatcher(shelterCommandsEnabled,
                AntPathRequestMatcher("/api/v1/shelters/{id}", "PUT"))
        val animalCommandsEnabled = RequestMatcher { "true".equals(animalCommandsEnabledFlag, ignoreCase = true) }
        val animalCommandWriting = AndRequestMatcher(animalCommandsEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/animals", "POST"),
                AntPathRequestMatcher("/api/v1/animals/{id}/status", "PATCH"),
                AntPathRequestMatcher("/api/v1/animals/{id}/description", "PATCH"),
                AntPathRequestMatcher("/api/v1/animals/{id}", "PUT"),
                AntPathRequestMatcher("/api/v1/animals/{id}", "DELETE")))
        val storeDirectOrdersEnabled = RequestMatcher { "true".equals(storeDirectOrdersEnabledFlag, ignoreCase = true) }
        val storeDirectOrderRoutes = AndRequestMatcher(storeDirectOrdersEnabled,
                AntPathRequestMatcher("/api/v1/orders/direct", "POST"))
        val storeCartsEnabled = RequestMatcher { "true".equals(storeCartsEnabledFlag, ignoreCase = true) }
        val storeCartRoutes = AndRequestMatcher(storeCartsEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/carts", "GET"),
                AntPathRequestMatcher("/api/v1/carts/items", "POST"),
                AntPathRequestMatcher("/api/v1/carts/items/{skuId}", "PATCH"),
                AntPathRequestMatcher("/api/v1/carts/items/{skuId}", "DELETE"),
                AntPathRequestMatcher("/api/v1/carts", "DELETE"),
                AntPathRequestMatcher("/api/v1/orders", "POST")))
        val storeOrderReading = AndRequestMatcher(OrRequestMatcher(storeDirectOrdersEnabled, storeCartsEnabled),
                OrRequestMatcher(AntPathRequestMatcher("/api/v1/orders/{orderId}", "GET"),
                        AntPathRequestMatcher("/api/v1/orders", "GET")))
        val animalLostSearchEnabled = RequestMatcher { "true".equals(animalLostSearchEnabledFlag, ignoreCase = true) }
        val animalLostSearchRoute = AndRequestMatcher(animalLostSearchEnabled,
                AntPathRequestMatcher("/api/v1/animals/lost-candidates", "POST"))
        val animalDiscoveryEnabled = RequestMatcher { animalDiscoveryEnabledFlag }
        val animalDiscoveryReading = AndRequestMatcher(animalDiscoveryEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/animals", "GET"),
                RegexRequestMatcher("^/api/v1/animals/[0-9]+(?:\\?.*)?\$", "GET")))
        val animalExtraDiscoveryEnabled = RequestMatcher {
            "true".equals(animalExtraDiscoveryFlag, ignoreCase = true) &&
                "postgresql".equals(animalQueryBackend, ignoreCase = true)
        }
        val animalExtraDiscoveryReading = AndRequestMatcher(animalExtraDiscoveryEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/animals/apms/{apmsDesertionNo}", "GET"),
                AntPathRequestMatcher("/api/v1/animals/expiring-soon", "GET")))
        val animalRecommendationsEnabled = RequestMatcher { animalRecommendationsEnabledFlag }
        val animalRecommendationReading = AndRequestMatcher(animalRecommendationsEnabled,
                RegexRequestMatcher("^/api/v1/animals/[0-9]+/similar(?:\\?.*)?\$", "GET"))
        val animalSheltersEnabled = RequestMatcher { animalSheltersEnabledFlag }
        val animalShelterReading = AndRequestMatcher(animalSheltersEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/shelters", "GET"),
                RegexRequestMatcher("^/api/v1/shelters/[0-9]+(?:\\?.*)?\$", "GET"),
                AntPathRequestMatcher("/api/v1/shelters/by-care-reg-no/{careRegNo}", "GET"),
                AntPathRequestMatcher("/api/v1/shelters/count", "GET")))
        val postgresqlSheltersEnabled = RequestMatcher {
            animalSheltersEnabledFlag &&
                "postgresql".equals(animalQueryBackend, ignoreCase = true)
        }
        val animalShelterDiscoveryReading = AndRequestMatcher(postgresqlSheltersEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/shelters/discovery", "GET"),
                RegexRequestMatcher("^/api/v1/shelters/[0-9]+/observations(?:\\?.*)?\$", "GET")))
        val animalStatisticsEnabled = RequestMatcher { animalStatisticsEnabledFlag }
        val publicAnimalStatistics = AndRequestMatcher(animalStatisticsEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/animals/stats/today", "GET"),
                AntPathRequestMatcher("/api/v1/animals/stats/status", "GET"),
                AntPathRequestMatcher("/api/v1/animals/stats/regional", "GET")))
        val adminAnimalStatistics = AndRequestMatcher(animalStatisticsEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/admin/stats/daily-animals", "GET"),
                AntPathRequestMatcher("/api/v1/admin/stats/intake-trend", "GET")))
        // Original animal-service had no own authentication; only the Gateway route required a JWT.
        val apmsManualEnabled = RequestMatcher { "true".equals(apmsManualFlag, ignoreCase = true) }
        val apmsManualSync = AndRequestMatcher(apmsManualEnabled, AntPathRequestMatcher("/api/v1/batch/apms/sync", "POST"))
        val storeWishlistEnabled = RequestMatcher { storeWishlistEnabledFlag }
        val storeWishlistWriting = AndRequestMatcher(storeWishlistEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/wishlists", "POST"),
                AntPathRequestMatcher("/api/v1/wishlists", "DELETE"),
                AntPathRequestMatcher("/api/v1/wishlists/{wishlistId}", "DELETE")))
        val storeCatalogEnabled = RequestMatcher { storeCatalogEnabledFlag }
        val storeCatalog = AndRequestMatcher(storeCatalogEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/products", "GET"),
                AntPathRequestMatcher("/api/v1/products/{productId}", "GET"),
                AntPathRequestMatcher("/api/v1/categories", "GET"),
                AntPathRequestMatcher("/api/v1/option-groups", "GET"),
                AntPathRequestMatcher("/api/v1/option-groups/{groupId}", "GET")))
        val storeMyPageEnabled = RequestMatcher { storeMyPageEnabledFlag }
        val storeMyPage = AndRequestMatcher(storeMyPageEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/users/me/wishlists", "GET"),
                AntPathRequestMatcher("/api/v1/users/me/orders", "GET"),
                AntPathRequestMatcher("/api/v1/users/me/cart", "GET")))
        val reportsEnabled = RequestMatcher { reportsEnabledFlag }
        val reportReading = AndRequestMatcher(reportsEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/reports", "GET"),
                AntPathRequestMatcher("/api/v1/reports/{reportId}", "GET")))
        val reportWriting = AndRequestMatcher(reportsEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/reports", "POST"),
                AntPathRequestMatcher("/api/v1/reports/{reportId}", "PUT"),
                AntPathRequestMatcher("/api/v1/reports/{reportId}", "DELETE")))
        val postCreation = AntPathRequestMatcher("/api/v1/posts", "POST")
        val postDeletion = AntPathRequestMatcher("/api/v1/posts/*", "DELETE")
        val postUpdate = AntPathRequestMatcher("/api/v1/posts/*", "PUT")
        val commentCreation = AntPathRequestMatcher("/api/v1/comments/posts/{postId}", "POST")
        val commentDeletion = AntPathRequestMatcher("/api/v1/comments/{commentId}", "DELETE")
        val commentUpdate = AntPathRequestMatcher("/api/v1/comments/{commentId}", "PUT")
        val userProfile = AntPathRequestMatcher("/api/v1/users/me", "GET")
        val userNickname = AntPathRequestMatcher("/api/v1/users/me/nickname", "PUT")
        val favoriteStatus = AntPathRequestMatcher("/api/v1/favorites/{animalId}/check", "GET")
        val noteRecipient = AntPathRequestMatcher("/api/v1/notes/recipients/{member}", "GET")
        val noteBlockStatus = AntPathRequestMatcher("/api/v1/notes/blocks/status", "GET")
        val noteBlockList = AntPathRequestMatcher("/api/v1/notes/blocks", "GET")
        val noteBlockRemove = AntPathRequestMatcher("/api/v1/notes/blocks/{member}", "DELETE")
        val noteBlockCreate = AntPathRequestMatcher("/api/v1/notes/blocks/{member}", "PUT")
        val noteNotifications = AntPathRequestMatcher("/api/v1/notes/notifications", "GET")
        val privateNoteList = AntPathRequestMatcher("/api/v1/notes", "GET")
        val noteStream = AntPathRequestMatcher("/api/v1/notes/stream", "GET")
        val noteRead = AntPathRequestMatcher("/api/v1/notes/{id}/read", "PUT")
        val noteFavorite = AntPathRequestMatcher("/api/v1/notes/{id}/favorite", "PUT")
        val noteSend = AntPathRequestMatcher("/api/v1/notes", "POST")
        val noteDeletion = AntPathRequestMatcher("/api/v1/notes/{id}", "DELETE")
        val privateNote = AndRequestMatcher(
                AntPathRequestMatcher("/api/v1/notes/{id}", "GET"),
                NegatedRequestMatcher(AntPathRequestMatcher("/api/v1/notes/stream", "GET")))
        val totalUserCount = AntPathRequestMatcher("/api/v1/admin/users/stats/total-users", "GET")
        val dailySignupStats = AntPathRequestMatcher("/api/v1/admin/users/stats/daily-signups", "GET")
        val signupPeriods = AntPathRequestMatcher("/api/v1/admin/users/stats/signup-periods", "GET")
        val userDeletion = AntPathRequestMatcher("/api/v1/admin/users/{userId}", "DELETE")
        val userDetail = AndRequestMatcher(
                AntPathRequestMatcher("/api/v1/admin/users/{userId}", "GET"),
                NegatedRequestMatcher(AntPathRequestMatcher("/api/v1/admin/users/search", "GET")))
        val userDirectory = OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/admin/users", "GET"),
                AntPathRequestMatcher("/api/v1/admin/users/search", "GET"))
        val userUpdate = AntPathRequestMatcher("/api/v1/admin/users/{userId}", "PUT")
        val sessionEnabled = RequestMatcher { sessionsEnabled }
        val sessionRefresh = AndRequestMatcher(sessionEnabled, AntPathRequestMatcher("/api/v1/auth/refresh", "POST"))
        val sessionLogout = AndRequestMatcher(sessionEnabled, AntPathRequestMatcher("/api/v1/auth/logout", "POST"))
        val visitCollectionEnabled = RequestMatcher { visitsEnabled && collectionEnabled }
        val visitIdentity = AndRequestMatcher(visitCollectionEnabled,
                AntPathRequestMatcher("/api/v1/visits/identity", "POST"))
        val visitCount = AndRequestMatcher(visitCollectionEnabled,
                AntPathRequestMatcher("/api/v1/visits/today", "POST"))
        val visitPeriod = AndRequestMatcher(visitCollectionEnabled,
                AntPathRequestMatcher("/api/v1/admin/visits/stats/period", "GET"))
        val chatEnabled = RequestMatcher { chatEnabledFlag }
        val chatSocket = AndRequestMatcher(chatEnabled, AntPathRequestMatcher("/api/v1/chats/socket", "GET"))
        // Keep the original 36-character UUID path constraint; query strings do not widen path matching.
        val chatRest = AndRequestMatcher(chatEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/chats", "GET"),
                AntPathRequestMatcher("/api/v1/chats/notifications", "GET"),
                AntPathRequestMatcher("/api/v1/chats/with/{member}", "GET"),
                AntPathRequestMatcher("/api/v1/chats/connection-ticket", "POST"),
                RegexRequestMatcher("^/api/v1/chats/[0-9a-fA-F-]{36}(?:/messages)?(?:\\?.*)?\$", "GET"),
                RegexRequestMatcher("^/api/v1/chats/[0-9a-fA-F-]{36}/read(?:\\?.*)?\$", "PUT"),
                RegexRequestMatcher("^/api/v1/chats/[0-9a-fA-F-]{36}/visibility(?:\\?.*)?\$", "DELETE")))
        val homeVideoEnabled = RequestMatcher { videoManagementEnabled }
        val homeVideoManagement = AndRequestMatcher(homeVideoEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/admin/videos", "GET"),
                AntPathRequestMatcher("/api/v1/admin/videos/preview", "POST"),
                AntPathRequestMatcher("/api/v1/admin/videos", "POST"),
                AntPathRequestMatcher("/api/v1/admin/videos/{id}", "PUT"),
                AntPathRequestMatcher("/api/v1/admin/videos/{id}/publication", "PUT"),
                AntPathRequestMatcher("/api/v1/admin/videos/order", "PUT"),
                AntPathRequestMatcher("/api/v1/admin/videos/{id}/recheck", "POST")))
        val adminPostList = AntPathRequestMatcher("/api/v1/admin/posts", "GET")
        val adminPostDetail = AntPathRequestMatcher("/api/v1/admin/posts/{postId}", "GET")
        val adminPostUpdate = AntPathRequestMatcher("/api/v1/admin/posts/{postId}", "PUT")
        val adminPostDelete = AntPathRequestMatcher("/api/v1/admin/posts/{postId}", "DELETE")
        val postStatsToday = AntPathRequestMatcher("/api/v1/admin/posts/stats/today", "GET")
        val postStatsPeriod = AntPathRequestMatcher("/api/v1/admin/posts/stats/period", "GET")
        val animalActivities = RequestMatcher { animalActivitiesEnabled }
        val userAnimalActivities = AndRequestMatcher(animalActivities, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/favorites/{animalId}", "POST"),
                AntPathRequestMatcher("/api/v1/favorites/{animalId}", "DELETE"),
                AntPathRequestMatcher("/api/v1/users/me/favorite-animals", "GET"),
                AntPathRequestMatcher("/api/v1/users/me/favorite-animal-notifications", "GET"),
                AntPathRequestMatcher("/api/v1/users/me/registered-animals", "GET")))
        val shelterEnabled = RequestMatcher { shelterMembershipEnabled }
        val shelterOwn = AndRequestMatcher(shelterEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/users/me/shelter-applications", "GET"),
                AntPathRequestMatcher("/api/v1/users/me/shelter-applications", "POST")))
        val shelterAdmin = AndRequestMatcher(shelterEnabled, OrRequestMatcher(
                AntPathRequestMatcher("/api/v1/admin/users/shelter-applications", "GET"),
                AntPathRequestMatcher("/api/v1/admin/users/shelter-applications/stats", "GET"),
                AntPathRequestMatcher("/api/v1/admin/users/shelter-applications/{id}", "GET"),
                AntPathRequestMatcher("/api/v1/admin/users/shelter-applications/{id}/approve", "POST"),
                AntPathRequestMatcher("/api/v1/admin/users/shelter-applications/{id}/reject", "POST"),
                AntPathRequestMatcher("/api/v1/admin/users/shelters/{careRegNo}/members", "GET")))
        val adminRoutes = OrRequestMatcher(
            apmsManualSync,
            shelterCommandAdmin,
            storeOrderAdministrationRoutes,
            adminAnimalStatistics,
            homeVideoManagement,
            shelterAdmin,
            userDirectory,
            adminPostList,
            adminPostDetail,
            adminPostUpdate,
            adminPostDelete,
            postStatsToday,
            postStatsPeriod,
            totalUserCount,
            dailySignupStats,
            signupPeriods,
            userDeletion,
            userDetail,
            userUpdate,
            visitPeriod,
        )
        val postWriting = OrRequestMatcher(
            apmsManualSync,
            animalImageWriting,
            catalogManagementWriting,
            paymentConfirmation,
            storeMockPayment,
            shelterCommandAdmin,
            shelterCommandUpdate,
            storeOrderAdministrationRoutes,
            storeCartRoutes,
            storeOrderReading,
            storeDirectOrderRoutes,
            animalCommandWriting,
            storeWishlistWriting,
            adminAnimalStatistics,
            storeMyPage,
            reportWriting,
            homeVideoManagement,
            userAnimalActivities,
            shelterOwn,
            shelterAdmin,
            userDirectory,
            chatRest,
            adminPostList,
            adminPostDetail,
            adminPostUpdate,
            adminPostDelete,
            postStatsToday,
            postStatsPeriod,
            postCreation,
            postDeletion,
            postUpdate,
            commentCreation,
            commentDeletion,
            commentUpdate,
            userProfile,
            userNickname,
            favoriteStatus,
            noteRecipient,
            noteBlockStatus,
            totalUserCount,
            dailySignupStats,
            signupPeriods,
            noteBlockList,
            noteNotifications,
            privateNote,
            privateNoteList,
            noteBlockRemove,
            noteBlockCreate,
            noteStream,
            noteRead,
            noteFavorite,
            noteDeletion,
            noteSend,
            userDeletion,
            userDetail,
            userUpdate,
            visitPeriod,
        )
        val streamContexts = RequestAttributeSecurityContextRepository()
        // Former Gateway health probe: only health (liveness/readiness included) is public; other actuator paths stay denied.
        val healthProbe = AntPathRequestMatcher("/actuator/health/**", "GET")
        val health = AntPathRequestMatcher("/actuator/health", "GET")
        return http.csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { context -> context.securityContextRepository(streamContexts).requireExplicitSave(true) }
            .authorizeHttpRequests { requests ->
                requests
                    // When an async stream fails after its response was committed, Tomcat renders the error page
                    // by INCLUDE instead of ERROR. Clients cannot create either dispatch; both only render /error.
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.INCLUDE).permitAll()
                    .requestMatchers(health, healthProbe).permitAll()
                    .requestMatchers(storeOrderAdministrationRoutes, catalogManagementWriting).hasRole("ADMIN")
                    .requestMatchers(
                        chatbotMessages,
                        galleryFeedRoutes,
                        animalTravelReading,
                        animalLostSearchRoute,
                        animalDiscoveryReading,
                        animalExtraDiscoveryReading,
                        animalRecommendationReading,
                        animalShelterReading,
                        animalShelterDiscoveryReading,
                        publicAnimalStatistics,
                        storeCatalog,
                    ).permitAll()
                    .requestMatchers(reportReading).permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/posts/read", "/api/v1/posts/read/*").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/posts/search").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/home/videos").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/visits/today").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/comments/posts/read/{postId}").permitAll()
                    .requestMatchers(visitIdentity, visitCount).permitAll()
                    // Cookie-only: the controller requires an allowed Origin and a JSON body type instead of a bearer token.
                    .requestMatchers(sessionRefresh, sessionLogout).permitAll()
                    .requestMatchers(chatSocket).permitAll()
                    .requestMatchers(adminRoutes).hasAuthority("ROLE_ADMIN")
                    .requestMatchers(postWriting).authenticated()
                    .anyRequest().denyAll()
            }
            // The preflight must be answered here, before authorization; rules mirror the Gateway globalcors block.
            .addFilterAt(GatewayCors.filter(corsOriginPatterns), CorsFilter::class.java)
            .addFilterBefore(
                PostBearerAuthenticationFilter(tokens, postWriting, noteStream, streamContexts),
                AnonymousAuthenticationFilter::class.java,
            )
            .exceptionHandling { errors ->
                errors
                    .authenticationEntryPoint(AuthenticationEntryPoint { _, response, _ -> forbidden(response) })
                    .accessDeniedHandler(
                        AccessDeniedHandler { request, response, _ ->
                            forbidden(
                                response,
                                if (adminRoutes.matches(request)) "관리자 권한이 필요합니다." else "접근할 수 없습니다.",
                            )
                        },
                    )
            }
            .build()
    }

    private companion object {
        @Throws(IOException::class)
        fun forbidden(response: HttpServletResponse, message: String = "접근할 수 없습니다.") {
            response.status = 403
            response.characterEncoding = StandardCharsets.UTF_8.name()
            response.contentType = "application/json"
            response.writer.write("{\"code\":403,\"message\":\"$message\",\"data\":null}")
        }
    }
}
