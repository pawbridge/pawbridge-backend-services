package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.homevideo.application.CandidateSource
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import com.pawbridge.backend.community.internal.homevideo.domain.CandidateVideo
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** YouTube Data API v3. [endpoint]는 API 뿌리 주소(`https://www.googleapis.com/youtube/v3`)다. */
class YouTubeVideoClient(
    private val mapper: ObjectMapper,
    private val apiKey: String,
    private val http: HttpClient,
    private val endpoint: URI,
) : CandidateSource {

    fun lookup(ids: List<String>): Map<String, Metadata> {
        if (isBlank(apiKey)) {
            throw unavailable()
        }
        if (ids.isEmpty() || ids.size > 50 || ids.any { id -> !VIDEO_ID.matches(id) }) {
            throw IllegalArgumentException("Invalid bounded video ID batch")
        }
        val fields = URLEncoder.encode(
            "items(id,snippet(title,channelTitle,thumbnails,liveBroadcastContent)," +
                "contentDetails(duration,regionRestriction,contentRating)," +
                "status(privacyStatus,uploadStatus,embeddable))",
            StandardCharsets.UTF_8,
        )
        val query = "?part=snippet,contentDetails,status&id=" + ids.joinToString(",") +
            "&fields=" + fields +
            "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
        val root = get("/videos", query, MAX_BODY_BYTES)
        if (!root.path("items").isArray) {
            throw unavailable()
        }
        val result = HashMap<String, Metadata>()
        for (item in root.path("items")) {
            val id = item.path("id").asText()
            if (!ids.contains(id)) {
                continue
            }
            val snippet = item.path("snippet")
            val title = snippet.path("title").asText()
            val channel = snippet.path("channelTitle").asText()
            val thumbnail = thumbnail(snippet)
            val duration: Long
            try {
                duration = Duration.parse(item.path("contentDetails").path("duration").asText()).toSeconds()
            } catch (failure: DateTimeParseException) {
                throw unavailable()
            }
            val playable = playable(item, duration, title, channel, thumbnail)
            result[id] = Metadata(id, title, channel, thumbnail, duration, playable)
        }
        return java.util.Map.copyOf(result)
    }

    override fun search(query: String, publishedAfter: Instant, pages: Int): List<String> {
        if (isBlank(apiKey)) {
            throw unavailable()
        }
        val ids = LinkedHashSet<String>()
        var pageToken = ""
        for (page in 0 until pages) {
            val parameters = linkedMapOf(
                "part" to "id",
                "type" to "video",
                "q" to query,
                "maxResults" to "50",
                "order" to "relevance",
                "regionCode" to "KR",
                "relevanceLanguage" to "ko",
                "safeSearch" to "strict",
                "videoEmbeddable" to "true",
                "videoSyndicated" to "true",
                "publishedAfter" to publishedAfter.truncatedTo(ChronoUnit.SECONDS).toString(),
                "fields" to "nextPageToken,items(id(videoId))",
            )
            if (pageToken.isNotEmpty()) {
                parameters["pageToken"] = pageToken
            }
            val root = get("/search", queryString(parameters), MAX_BODY_BYTES)
            if (!root.path("items").isArray) {
                throw unavailable()
            }
            for (item in root.path("items")) {
                val id = item.path("id").path("videoId").asText()
                if (VIDEO_ID.matches(id)) {
                    ids.add(id)
                }
            }
            pageToken = root.path("nextPageToken").asText()
            if (!PAGE_TOKEN.matches(pageToken)) {
                break
            }
        }
        return ids.toList()
    }

    override fun details(ids: List<String>): List<CandidateVideo> {
        if (isBlank(apiKey)) {
            throw unavailable()
        }
        if (ids.any { id -> !VIDEO_ID.matches(id) }) {
            throw IllegalArgumentException("Invalid video ID")
        }
        val videos = ArrayList<CandidateVideo>()
        // 설명이 영상마다 5,000자까지라 응답 크기를 묶어 두려고 작게 나눠 묻는다.
        for (batch in ids.distinct().chunked(DETAILS_BATCH_SIZE)) {
            val parameters = linkedMapOf(
                "part" to "snippet,contentDetails,statistics,status",
                "id" to batch.joinToString(","),
                "fields" to "items(id,snippet(title,description,channelId,channelTitle,publishedAt,thumbnails," +
                    "liveBroadcastContent),contentDetails(duration,regionRestriction,contentRating)," +
                    "statistics(viewCount),status(privacyStatus,uploadStatus,embeddable))",
            )
            val root = get("/videos", queryString(parameters), DETAILS_MAX_BODY_BYTES)
            if (!root.path("items").isArray) {
                throw unavailable()
            }
            for (item in root.path("items")) {
                val video = candidate(item, batch)
                if (video != null) {
                    videos.add(video)
                }
            }
        }
        return videos
    }

    private fun candidate(item: JsonNode, requested: List<String>): CandidateVideo? {
        val id = item.path("id").asText()
        val snippet = item.path("snippet")
        val channelId = snippet.path("channelId").asText()
        if (!requested.contains(id) || !CHANNEL_ID.matches(channelId)) {
            return null
        }
        val title = snippet.path("title").asText()
        val channel = snippet.path("channelTitle").asText()
        val publishedAt: Instant
        val duration: Long
        try {
            publishedAt = Instant.parse(snippet.path("publishedAt").asText())
            duration = Duration.parse(item.path("contentDetails").path("duration").asText()).toSeconds()
        } catch (failure: DateTimeParseException) {
            return null
        }
        // 조회수를 숨긴 영상은 값이 없다. 추측하지 않고 0으로 두어 조회수 규칙에서 빠지게 한다.
        val views = item.path("statistics").path("viewCount").asText().toLongOrNull() ?: 0L
        return CandidateVideo(
            id,
            channelId,
            channel,
            title,
            snippet.path("description").asText(),
            duration,
            views,
            publishedAt,
            playable(item, duration, title, channel, thumbnail(snippet)),
        )
    }

    private fun get(path: String, query: String, maxBytes: Long): JsonNode {
        var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
        try {
            val request = HttpRequest.newBuilder(URI.create(endpoint.toString() + path + query))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
            val sent = http.sendAsync(request, HttpResponse.BodyHandler<ByteArray> { BoundedBody(maxBytes) })
            pending = sent
            val response = sent.get(10, TimeUnit.SECONDS)
            if (response.statusCode() != 200) {
                throw unavailable()
            }
            return mapper.readTree(response.body())
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw unavailable()
        } catch (failure: Exception) {
            // HTTP exceptions can contain the request URI and key. Never retain their cause or text.
            throw unavailable()
        } finally {
            if (pending != null && !pending.isDone) {
                pending.cancel(true)
            }
        }
    }

    private fun queryString(parameters: Map<String, String>): String {
        val pairs = parameters.entries.map { (name, value) ->
            name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }
        return "?" + pairs.joinToString("&") + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
    }

    private companion object {
        private const val MAX_BODY_BYTES = 524_288L
        private const val DETAILS_MAX_BODY_BYTES = 2_097_152L
        private const val DETAILS_BATCH_SIZE = 25
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val CHANNEL_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val PAGE_TOKEN = Regex("[A-Za-z0-9_-]{1,128}")

        private fun thumbnail(snippet: JsonNode): String {
            var thumbnail = snippet.path("thumbnails").path("high").path("url").asText()
            if (isBlank(thumbnail)) {
                thumbnail = snippet.path("thumbnails").path("medium").path("url").asText()
            }
            if (isBlank(thumbnail)) {
                thumbnail = snippet.path("thumbnails").path("default").path("url").asText()
            }
            return thumbnail
        }

        private fun playable(item: JsonNode, duration: Long, title: String, channel: String, thumbnail: String): Boolean {
            val snippet = item.path("snippet")
            val details = item.path("contentDetails")
            val status = item.path("status")
            return "public" == status.path("privacyStatus").asText() &&
                "processed" == status.path("uploadStatus").asText() &&
                status.path("embeddable").asBoolean(false) &&
                "none" == snippet.path("liveBroadcastContent").asText() &&
                details.path("contentRating").path("ytRating").isMissingNode &&
                allowsKorea(details.path("regionRestriction")) &&
                duration > 0 &&
                !isBlank(title) &&
                title.length <= 500 &&
                !isBlank(channel) &&
                channel.length <= 300 &&
                safeThumbnail(thumbnail)
        }

        // Java String.isBlank semantics (Character.isWhitespace); Kotlin's isBlank also treats NBSP as blank.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        private fun safeThumbnail(value: String): Boolean {
            try {
                val uri = URI.create(value)
                return "https" == uri.scheme &&
                    "i.ytimg.com" == uri.host &&
                    uri.userInfo == null &&
                    uri.port == -1 &&
                    value.length <= 2048
            } catch (failure: IllegalArgumentException) {
                return false
            }
        }

        private fun allowsKorea(region: JsonNode): Boolean {
            if (region.path("blocked").isArray) {
                for (code in region.path("blocked")) {
                    if ("KR" == code.asText()) {
                        return false
                    }
                }
            }
            if (region.path("allowed").isArray) {
                for (code in region.path("allowed")) {
                    if ("KR" == code.asText()) {
                        return true
                    }
                }
                return false
            }
            return true
        }

        private fun unavailable(): ResponseStatusException =
            ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "YouTube 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
            )
    }
}
