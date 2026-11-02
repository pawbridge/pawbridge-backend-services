package com.pawbridge.backend

/**
 * Every route the main security chain (ReadSecurityConfiguration) opens, with who may call it and which
 * property flags have to be on. One table drives SecurityChainHttpTest and SecurityChainFlagHttpTest.
 */
enum class Access {
    /** permitAll: no token is read at all. */
    PUBLIC,

    /** Any valid token. */
    MEMBER,

    /** ROLE_ADMIN only; a rejected caller gets the admin message. */
    ADMIN,

    /** ROLE_ADMIN only; a rejected caller gets the generic message. */
    ADMIN_GENERIC,
}

/** A property the chain reads. [lenient] flags are Boolean in the chain (on/yes/1 count as true). */
data class Requirement(val property: String, val lenient: Boolean = false, val value: String = "true") {
    fun satisfiedBy(properties: Map<String, String>): Boolean {
        val actual = properties[property] ?: return false
        return if (property == BACKEND) {
            actual == "postgresql"
        } else if (lenient) {
            actual.lowercase() in setOf("true", "on", "yes", "1")
        } else {
            actual.equals("true", ignoreCase = true)
        }
    }

    fun enabledValue(): String = if (property == BACKEND) "postgresql" else "true"

    companion object {
        const val BACKEND = "pawbridge.animal-query.backend"
    }
}

data class Route(
    val method: String,
    val template: String,
    val access: Access,
    val requires: List<Requirement> = emptyList(),
    val example: String = template.replace(Regex("\\{[^}]*}"), "7"),
    val pattern: Regex = Regex(
        template.replace(Regex("\\{[^}]*}"), "[^/]+").replace("*", "[^/]*"),
    ),
) {
    fun matches(otherMethod: String, path: String): Boolean {
        return method == otherMethod && pattern.matches(path)
    }

    fun malformedExample(): String = template.replace(Regex("\\{[^}]*}"), "abc").replace("*", "abc")

    fun isOpenIn(properties: Map<String, String>): Boolean = requires.all { it.satisfiedBy(properties) }

    override fun toString(): String = "$method $template"
}

object SecurityRoutes {
    private fun flag(name: String, lenient: Boolean = false) = Requirement("pawbridge.$name.enabled", lenient)

    private val backend = Requirement(Requirement.BACKEND)
    private const val UUID = "123e4567-e89b-12d3-a456-426614174000"
    private val uuidPattern = "[0-9a-fA-F-]{36}"

    private val animalImages = listOf(flag("animal-images"))
    private val catalogManagement = listOf(flag("store-catalog-management"))
    private val payments = listOf(flag("payments"))
    private val mockPayment = listOf(flag("store-mock-payment"))
    private val chatbot = listOf(flag("animal-chatbot"), backend)
    private val galleryFeed = listOf(Requirement("lost-gallery-feed.enabled"))
    private val orderAdministration = listOf(flag("store-order-administration"))
    private val travel = listOf(flag("animal-travel"), backend)
    private val shelterCommands = listOf(flag("shelter-commands"))
    private val animalCommands = listOf(flag("animal-commands"))
    private val directOrders = listOf(flag("store-direct-orders"))
    private val carts = listOf(flag("store-carts"))
    private val lostSearch = listOf(flag("animal-lost-search"))
    private val discovery = listOf(flag("animal-discovery", lenient = true))
    private val extraDiscovery = listOf(flag("animal-discovery"), backend)
    private val recommendations = listOf(flag("animal-recommendations", lenient = true))
    private val shelters = listOf(flag("animal-shelters", lenient = true))
    private val shelterDiscovery = listOf(flag("animal-shelters", lenient = true), backend)
    private val statistics = listOf(flag("animal-statistics", lenient = true))
    private val apmsManual = listOf(flag("apms-manual"))
    private val wishlist = listOf(flag("store-wishlist", lenient = true))
    private val catalog = listOf(flag("store-catalog", lenient = true))
    private val myPage = listOf(flag("user-store-mypage", lenient = true))
    private val reports = listOf(flag("animal-reports", lenient = true))
    private val sessions = listOf(Requirement("pawbridge.auth.sessions-enabled", lenient = true))
    private val visitCollection = listOf(
        Requirement("pawbridge.visits.enabled", lenient = true),
        Requirement("pawbridge.visits.collection-enabled", lenient = true),
    )
    private val chat = listOf(flag("chat", lenient = true))
    private val homeVideos = listOf(Requirement("pawbridge.home-videos.management-enabled", lenient = true))
    private val animalActivities = listOf(flag("user-animal-activities", lenient = true))
    private val shelterMembership = listOf(flag("shelter-membership", lenient = true))

    val routes: List<Route> = buildList {
        fun add(
            method: String,
            template: String,
            access: Access,
            requires: List<Requirement> = emptyList(),
            example: String? = null,
            pattern: String? = null,
        ) {
            val sample = example ?: template.replace(Regex("\\{[^}]*}"), "7")
            add(
                Route(
                    method,
                    template,
                    access,
                    requires,
                    sample,
                    pattern?.let { Regex(it) } ?: Regex(template.replace(Regex("\\{[^}]*}"), "[^/]+").replace("*", "[^/]*")),
                ),
            )
        }

        // Always on: community.
        add("POST", "/api/v1/posts", Access.MEMBER)
        add("PUT", "/api/v1/posts/*", Access.MEMBER)
        add("DELETE", "/api/v1/posts/*", Access.MEMBER)
        add("GET", "/api/v1/posts/read", Access.PUBLIC)
        add("GET", "/api/v1/posts/read/*", Access.PUBLIC)
        add("GET", "/api/v1/posts/search", Access.PUBLIC)
        add("GET", "/api/v1/home/videos", Access.PUBLIC)
        add("GET", "/api/v1/comments/posts/read/{postId}", Access.PUBLIC)
        add("POST", "/api/v1/comments/posts/{postId}", Access.MEMBER)
        add("PUT", "/api/v1/comments/{commentId}", Access.MEMBER)
        add("DELETE", "/api/v1/comments/{commentId}", Access.MEMBER)
        add("GET", "/api/v1/visits/today", Access.PUBLIC)
        // Always on: user.
        add("GET", "/api/v1/users/me", Access.MEMBER)
        add("PUT", "/api/v1/users/me/nickname", Access.MEMBER)
        add("GET", "/api/v1/favorites/{animalId}/check", Access.MEMBER)
        // Always on: notes.
        add("GET", "/api/v1/notes/recipients/{member}", Access.MEMBER)
        add("GET", "/api/v1/notes/blocks/status", Access.MEMBER)
        add("GET", "/api/v1/notes/blocks", Access.MEMBER)
        add("DELETE", "/api/v1/notes/blocks/{member}", Access.MEMBER)
        add("PUT", "/api/v1/notes/blocks/{member}", Access.MEMBER)
        add("GET", "/api/v1/notes/notifications", Access.MEMBER)
        add("GET", "/api/v1/notes", Access.MEMBER)
        add("POST", "/api/v1/notes", Access.MEMBER)
        add("GET", "/api/v1/notes/stream", Access.MEMBER)
        add("GET", "/api/v1/notes/{id}", Access.MEMBER, example = "/api/v1/notes/$UUID")
        add("PUT", "/api/v1/notes/{id}/read", Access.MEMBER)
        add("PUT", "/api/v1/notes/{id}/favorite", Access.MEMBER)
        add("DELETE", "/api/v1/notes/{id}", Access.MEMBER)
        // Always on: administration.
        add("GET", "/api/v1/admin/posts", Access.ADMIN)
        add("GET", "/api/v1/admin/posts/{postId}", Access.ADMIN)
        add("PUT", "/api/v1/admin/posts/{postId}", Access.ADMIN)
        add("DELETE", "/api/v1/admin/posts/{postId}", Access.ADMIN)
        add("GET", "/api/v1/admin/posts/stats/today", Access.ADMIN)
        add("GET", "/api/v1/admin/posts/stats/period", Access.ADMIN)
        add("GET", "/api/v1/admin/users/stats/total-users", Access.ADMIN)
        add("GET", "/api/v1/admin/users/stats/daily-signups", Access.ADMIN)
        add("GET", "/api/v1/admin/users/stats/signup-periods", Access.ADMIN)
        add("GET", "/api/v1/admin/users", Access.ADMIN)
        add("GET", "/api/v1/admin/users/search", Access.ADMIN)
        add("GET", "/api/v1/admin/users/{userId}", Access.ADMIN)
        add("PUT", "/api/v1/admin/users/{userId}", Access.ADMIN)
        add("DELETE", "/api/v1/admin/users/{userId}", Access.ADMIN)

        // Flag: animal images.
        add("POST", "/api/v1/animals/images", Access.MEMBER, animalImages)
        add("POST", "/api/v1/animals/images/multiple", Access.MEMBER, animalImages)
        // Flag: store catalog management.
        add("POST", "/api/v1/products", Access.ADMIN_GENERIC, catalogManagement)
        add("PATCH", "/api/v1/products/{productId}", Access.ADMIN_GENERIC, catalogManagement)
        add("DELETE", "/api/v1/products/{productId}", Access.ADMIN_GENERIC, catalogManagement)
        add("POST", "/api/v1/images", Access.ADMIN_GENERIC, catalogManagement)
        add("POST", "/api/v1/option-groups", Access.ADMIN_GENERIC, catalogManagement)
        add("PUT", "/api/v1/option-groups/{groupId}", Access.ADMIN_GENERIC, catalogManagement)
        add("DELETE", "/api/v1/option-groups/{groupId}", Access.ADMIN_GENERIC, catalogManagement)
        add("POST", "/api/v1/option-groups/{groupId}/values", Access.ADMIN_GENERIC, catalogManagement)
        add("PUT", "/api/v1/option-groups/values/{valueId}", Access.ADMIN_GENERIC, catalogManagement)
        add("DELETE", "/api/v1/option-groups/values/{valueId}", Access.ADMIN_GENERIC, catalogManagement)
        add("POST", "/api/v1/categories", Access.ADMIN_GENERIC, catalogManagement)
        add("PUT", "/api/v1/categories/{categoryId}", Access.ADMIN_GENERIC, catalogManagement)
        add("DELETE", "/api/v1/categories/{categoryId}", Access.ADMIN_GENERIC, catalogManagement)
        // Flag: payments.
        add("POST", "/api/v1/payments/confirm", Access.MEMBER, payments)
        add("POST", "/api/v1/orders/{orderId}/payment", Access.MEMBER, mockPayment)
        // Flag: chatbot.
        add("POST", "/api/v1/animals/{animalId}/chat/messages", Access.PUBLIC, chatbot)
        // Flag: lost gallery feed.
        add("GET", "/internal/animals/lost-gallery", Access.PUBLIC, galleryFeed)
        add("POST", "/internal/animals/lost-gallery/snapshots", Access.PUBLIC, galleryFeed)
        add("GET", "/internal/animals/lost-gallery/snapshots/{id}", Access.PUBLIC, galleryFeed)
        add("GET", "/internal/animals/lost-gallery/snapshots/{id}/pages", Access.PUBLIC, galleryFeed)
        add("DELETE", "/internal/animals/lost-gallery/snapshots/{id}", Access.PUBLIC, galleryFeed)
        // Flag: store order administration.
        add("GET", "/api/v1/admin/orders", Access.ADMIN, orderAdministration)
        add("GET", "/api/v1/admin/orders/{orderId}", Access.ADMIN, orderAdministration)
        add("PATCH", "/api/v1/admin/orders/{orderId}/status", Access.ADMIN, orderAdministration)
        add("PATCH", "/api/v1/admin/orders/{orderId}/delivery-status", Access.ADMIN, orderAdministration)
        // Flag: travel.
        add("GET", "/api/v1/places/regions", Access.PUBLIC, travel)
        add("GET", "/api/v1/places", Access.PUBLIC, travel)
        add("GET", "/api/v1/places/{contentId}", Access.PUBLIC, travel)
        // Flag: shelter commands.
        add("POST", "/api/v1/shelters", Access.ADMIN, shelterCommands)
        add("DELETE", "/api/v1/shelters/{id}", Access.ADMIN, shelterCommands)
        add("PUT", "/api/v1/shelters/{id}", Access.MEMBER, shelterCommands)
        // Flag: animal commands.
        add("POST", "/api/v1/animals", Access.MEMBER, animalCommands)
        add("PATCH", "/api/v1/animals/{id}/status", Access.MEMBER, animalCommands)
        add("PATCH", "/api/v1/animals/{id}/description", Access.MEMBER, animalCommands)
        add("PUT", "/api/v1/animals/{id}", Access.MEMBER, animalCommands)
        add("DELETE", "/api/v1/animals/{id}", Access.MEMBER, animalCommands)
        // Flag: orders and carts.
        add("POST", "/api/v1/orders/direct", Access.MEMBER, directOrders)
        add("GET", "/api/v1/orders/{orderId}", Access.MEMBER, directOrders)
        add("GET", "/api/v1/orders", Access.MEMBER, directOrders)
        add("GET", "/api/v1/orders/{orderId}", Access.MEMBER, carts)
        add("GET", "/api/v1/orders", Access.MEMBER, carts)
        add("GET", "/api/v1/carts", Access.MEMBER, carts)
        add("POST", "/api/v1/carts/items", Access.MEMBER, carts)
        add("PATCH", "/api/v1/carts/items/{skuId}", Access.MEMBER, carts)
        add("DELETE", "/api/v1/carts/items/{skuId}", Access.MEMBER, carts)
        add("DELETE", "/api/v1/carts", Access.MEMBER, carts)
        add("POST", "/api/v1/orders", Access.MEMBER, carts)
        // Flag: lost search.
        add("POST", "/api/v1/animals/lost-candidates", Access.PUBLIC, lostSearch)
        // Flag: animal discovery, recommendations, shelters, statistics.
        add("GET", "/api/v1/animals", Access.PUBLIC, discovery)
        add("GET", "/api/v1/animals/{id}", Access.PUBLIC, discovery, example = "/api/v1/animals/7", pattern = "/api/v1/animals/[0-9]+")
        add("GET", "/api/v1/animals/apms/{apmsDesertionNo}", Access.PUBLIC, extraDiscovery)
        add("GET", "/api/v1/animals/expiring-soon", Access.PUBLIC, extraDiscovery)
        add("GET", "/api/v1/animals/{id}/similar", Access.PUBLIC, recommendations, example = "/api/v1/animals/7/similar", pattern = "/api/v1/animals/[0-9]+/similar")
        add("GET", "/api/v1/shelters", Access.PUBLIC, shelters)
        add("GET", "/api/v1/shelters/{id}", Access.PUBLIC, shelters, example = "/api/v1/shelters/7", pattern = "/api/v1/shelters/[0-9]+")
        add("GET", "/api/v1/shelters/by-care-reg-no/{careRegNo}", Access.PUBLIC, shelters)
        add("GET", "/api/v1/shelters/count", Access.PUBLIC, shelters)
        add("GET", "/api/v1/shelters/discovery", Access.PUBLIC, shelterDiscovery)
        add("GET", "/api/v1/shelters/{id}/observations", Access.PUBLIC, shelterDiscovery, example = "/api/v1/shelters/7/observations", pattern = "/api/v1/shelters/[0-9]+/observations")
        add("GET", "/api/v1/animals/stats/today", Access.PUBLIC, statistics)
        add("GET", "/api/v1/animals/stats/status", Access.PUBLIC, statistics)
        add("GET", "/api/v1/animals/stats/regional", Access.PUBLIC, statistics)
        add("GET", "/api/v1/admin/stats/daily-animals", Access.ADMIN, statistics)
        add("GET", "/api/v1/admin/stats/intake-trend", Access.ADMIN, statistics)
        // Flag: APMS manual sync.
        add("POST", "/api/v1/batch/apms/sync", Access.ADMIN, apmsManual)
        // Flag: wishlist, catalog reads, my page, reports.
        add("POST", "/api/v1/wishlists", Access.MEMBER, wishlist)
        add("DELETE", "/api/v1/wishlists", Access.MEMBER, wishlist)
        add("DELETE", "/api/v1/wishlists/{wishlistId}", Access.MEMBER, wishlist)
        add("GET", "/api/v1/products", Access.PUBLIC, catalog)
        add("GET", "/api/v1/products/{productId}", Access.PUBLIC, catalog)
        add("GET", "/api/v1/categories", Access.PUBLIC, catalog)
        add("GET", "/api/v1/option-groups", Access.PUBLIC, catalog)
        add("GET", "/api/v1/option-groups/{groupId}", Access.PUBLIC, catalog)
        add("GET", "/api/v1/users/me/wishlists", Access.MEMBER, myPage)
        add("GET", "/api/v1/users/me/orders", Access.MEMBER, myPage)
        add("GET", "/api/v1/users/me/cart", Access.MEMBER, myPage)
        add("GET", "/api/v1/reports", Access.PUBLIC, reports)
        add("GET", "/api/v1/reports/{reportId}", Access.PUBLIC, reports)
        add("POST", "/api/v1/reports", Access.MEMBER, reports)
        add("PUT", "/api/v1/reports/{reportId}", Access.MEMBER, reports)
        add("DELETE", "/api/v1/reports/{reportId}", Access.MEMBER, reports)
        // Flag: local account flows.
        // Flag: sessions and visits.
        add("POST", "/api/v1/auth/refresh", Access.PUBLIC, sessions)
        add("POST", "/api/v1/auth/logout", Access.PUBLIC, sessions)
        add("POST", "/api/v1/visits/identity", Access.PUBLIC, visitCollection)
        add("POST", "/api/v1/visits/today", Access.PUBLIC, visitCollection)
        add("GET", "/api/v1/admin/visits/stats/period", Access.ADMIN, visitCollection)
        // Flag: chat.
        add("GET", "/api/v1/chats/socket", Access.PUBLIC, chat)
        add("GET", "/api/v1/chats", Access.MEMBER, chat)
        add("GET", "/api/v1/chats/notifications", Access.MEMBER, chat)
        add("GET", "/api/v1/chats/with/{member}", Access.MEMBER, chat)
        add("POST", "/api/v1/chats/connection-ticket", Access.MEMBER, chat)
        add("GET", "/api/v1/chats/{room}", Access.MEMBER, chat, example = "/api/v1/chats/$UUID", pattern = "/api/v1/chats/$uuidPattern")
        add("GET", "/api/v1/chats/{room}/messages", Access.MEMBER, chat, example = "/api/v1/chats/$UUID/messages", pattern = "/api/v1/chats/$uuidPattern/messages")
        add("PUT", "/api/v1/chats/{room}/read", Access.MEMBER, chat, example = "/api/v1/chats/$UUID/read", pattern = "/api/v1/chats/$uuidPattern/read")
        add("DELETE", "/api/v1/chats/{room}/visibility", Access.MEMBER, chat, example = "/api/v1/chats/$UUID/visibility", pattern = "/api/v1/chats/$uuidPattern/visibility")
        // Flag: home video management.
        add("GET", "/api/v1/admin/videos", Access.ADMIN, homeVideos)
        add("POST", "/api/v1/admin/videos/preview", Access.ADMIN, homeVideos)
        add("POST", "/api/v1/admin/videos", Access.ADMIN, homeVideos)
        add("PUT", "/api/v1/admin/videos/{id}", Access.ADMIN, homeVideos)
        add("PUT", "/api/v1/admin/videos/{id}/publication", Access.ADMIN, homeVideos)
        add("PUT", "/api/v1/admin/videos/order", Access.ADMIN, homeVideos)
        add("POST", "/api/v1/admin/videos/{id}/recheck", Access.ADMIN, homeVideos)
        // Flag: user animal activities.
        add("POST", "/api/v1/favorites/{animalId}", Access.MEMBER, animalActivities)
        add("DELETE", "/api/v1/favorites/{animalId}", Access.MEMBER, animalActivities)
        add("GET", "/api/v1/users/me/favorite-animals", Access.MEMBER, animalActivities)
        add("GET", "/api/v1/users/me/favorite-animal-notifications", Access.MEMBER, animalActivities)
        add("GET", "/api/v1/users/me/registered-animals", Access.MEMBER, animalActivities)
        // Flag: shelter membership.
        add("GET", "/api/v1/users/me/shelter-applications", Access.MEMBER, shelterMembership)
        add("POST", "/api/v1/users/me/shelter-applications", Access.MEMBER, shelterMembership)
        add("GET", "/api/v1/admin/users/shelter-applications", Access.ADMIN, shelterMembership)
        add("GET", "/api/v1/admin/users/shelter-applications/stats", Access.ADMIN, shelterMembership)
        add("GET", "/api/v1/admin/users/shelter-applications/{id}", Access.ADMIN, shelterMembership)
        add("POST", "/api/v1/admin/users/shelter-applications/{id}/approve", Access.ADMIN, shelterMembership)
        add("POST", "/api/v1/admin/users/shelter-applications/{id}/reject", Access.ADMIN, shelterMembership)
        add("GET", "/api/v1/admin/users/shelters/{careRegNo}/members", Access.ADMIN, shelterMembership)
    }

    /** Every property the table reads, switched on. */
    val everythingOn: Map<String, String> = routes.flatMap { it.requires }
        .distinctBy { it.property }
        .associate { it.property to it.enabledValue() }

    /** The distinct flag combinations, one per feature. */
    val flagGroups: List<List<Requirement>> = routes.map { it.requires }.filter { it.isNotEmpty() }.distinct()

    fun isOpen(properties: Map<String, String>, method: String, path: String): Boolean {
        return routes.any { it.isOpenIn(properties) && it.matches(method, path) }
    }

    fun propertiesOf(group: List<Requirement>): Map<String, String> {
        return group.associate { it.property to it.enabledValue() }
    }

    fun describe(group: List<Requirement>): String = group.joinToString("+") { it.property.removePrefix("pawbridge.") }
}
