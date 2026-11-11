package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.server.ResponseStatusException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Instant

class YouTubeVideoClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: YouTubeVideoClient
    private lateinit var payload: String
    private var status = 200
    private var query: String? = null
    private val videoQueries = ArrayList<String>()
    private val searchQueries = ArrayList<String>()
    private lateinit var searchPages: Map<String, String>
    private lateinit var http: HttpClient

    @BeforeEach
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/videos") { exchange ->
            query = exchange.requestURI.rawQuery
            videoQueries.add(exchange.requestURI.rawQuery)
            val body = payload.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }
        server.createContext("/search") { exchange ->
            val rawQuery = exchange.requestURI.rawQuery
            searchQueries.add(rawQuery)
            val page = if (rawQuery.contains("pageToken=NEXT")) "second" else "first"
            val body = searchPages.getValue(page).toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }
        server.start()
        http = HttpClient.newHttpClient()
        client = YouTubeVideoClient(
            ObjectMapper(),
            "synthetic-key",
            http,
            URI.create("http://127.0.0.1:" + server.address.port),
        )
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true", "")
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        http.close()
    }

    @Test
    fun `공개되고 임베드 가능한 영상일 때__조회하면__메타데이터를 돌려주고 ids 요청 한 번만 보낸다`() {
        // given
        val ids = listOf(ID, "OtherVideo1")

        // when
        val result = client.lookup(ids)

        // then
        assertThat(result[ID]!!.available).isTrue()
        assertThat(result[ID]!!.durationSeconds).isEqualTo(204)
        assertThat(query).contains("part=snippet,contentDetails,status", "id=AbCdEfGhI_1,OtherVideo1")
    }

    @ParameterizedTest
    @ValueSource(strings = ["private", "unlisted"])
    fun `비공개 영상일 때__조회하면__재생할 수 없다`(privacy: String) {
        // given
        payload = json("\"privacyStatus\":\"" + privacy + "\",\"uploadStatus\":\"processed\",\"embeddable\":true", "")

        // when
        val result = client.lookup(listOf(ID))

        // then
        assertThat(result[ID]!!.available).isFalse()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            ",\"regionRestriction\":{\"blocked\":[\"KR\"]}",
            ",\"regionRestriction\":{\"allowed\":[\"US\"]}",
            ",\"contentRating\":{\"ytRating\":\"ytAgeRestricted\"}",
        ],
    )
    fun `지역 또는 연령 제한이 있을 때__조회하면__재생할 수 없다`(detail: String) {
        // given
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true", detail)

        // when
        val result = client.lookup(listOf(ID))

        // then
        assertThat(result[ID]!!.available).isFalse()
    }

    @Test
    fun `임베드가 꺼졌거나 라이브일 때__조회하면__재생할 수 없다`() {
        // given
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":false", "")

        // when
        val notEmbeddable = client.lookup(listOf(ID))

        // then
        assertThat(notEmbeddable[ID]!!.available).isFalse()

        // given
        payload = json("\"privacyStatus\":\"public\",\"uploadStatus\":\"processed\",\"embeddable\":true", "")
            .replace("\"none\"", "\"live\"")

        // when
        val live = client.lookup(listOf(ID))

        // then
        assertThat(live[ID]!!.available).isFalse()
    }

    @Test
    fun `성공했지만 목록이 비었을 때__조회하면__공급자 실패가 아니라 부재로 본다`() {
        // given
        payload = "{\"items\":[]}"

        // when
        val result = client.lookup(listOf(ID))

        // then
        assertThat(result).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(ints = [403, 429, 500])
    fun `공급자가 실패할 때__조회하면__키나 원인 없이 정제된 503을 돌려준다`(code: Int) {
        // given
        status = code
        payload = "synthetic-key should never escape"

        // when
        val thrown = assertThatThrownBy { client.lookup(listOf(ID)) }

        // then
        thrown
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageNotContaining("synthetic-key")
            .hasMessageNotContaining("http://")
            .hasMessageNotContaining("key=")
            .hasNoCause()
            .satisfies({ e ->
                assertThat((e as ResponseStatusException).statusCode.value()).isEqualTo(503)
            })
    }

    @Test
    fun `공급자 응답이 잘못된 형식일 때__조회하면__영상 부재가 아니라 실패로 본다`() {
        // given
        payload = "{\"error\":\"synthetic-key\"}"

        // when
        val thrown = assertThatThrownBy { client.lookup(listOf(ID)) }

        // then
        thrown
            .isInstanceOf(ResponseStatusException::class.java)
            .hasNoCause()
    }

    @Test
    fun `공급자 본문이 너무 클 때__조회하면__제한된 정제 실패를 돌려준다`() {
        // given
        payload = " ".repeat(524_289)

        // when
        val thrown = assertThatThrownBy { client.lookup(listOf(ID)) }

        // then
        thrown
            .isInstanceOf(ResponseStatusException::class.java)
            .hasNoCause()
            .hasMessageNotContaining("synthetic-key")
    }

    @Test
    fun `썸네일 호스트가 외부일 때__조회하면__재생할 수 없다`() {
        // given
        payload = payload.replace("https://i.ytimg.com/", "https://untrusted.example/")

        // when
        val result = client.lookup(listOf(ID))

        // then
        assertThat(result[ID]!!.available).isFalse()
    }

    @Test
    fun `검색 결과가 두 쪽일 때__검색하면__다음 쪽까지 읽고 순서를 지켜 중복 없이 돌려준다`() {
        // given
        searchPages = mapOf(
            "first" to "{\"nextPageToken\":\"NEXT\",\"items\":[{\"id\":{\"videoId\":\"AAAAAAAAAA1\"}}," +
                "{\"id\":{\"videoId\":\"BBBBBBBBBB2\"}}]}",
            "second" to "{\"items\":[{\"id\":{\"videoId\":\"BBBBBBBBBB2\"}},{\"id\":{\"videoId\":\"bad id\"}}," +
                "{\"id\":{\"videoId\":\"CCCCCCCCCC3\"}}]}",
        )

        // when
        val ids = client.search("유기견 입양 브이로그", Instant.parse("2023-11-11T00:00:00.123Z"), 3)

        // then
        assertThat(ids).containsExactly("AAAAAAAAAA1", "BBBBBBBBBB2", "CCCCCCCCCC3")
        assertThat(searchQueries).hasSize(2)
        assertThat(searchQueries[0]).contains(
            "part=id",
            "type=video",
            "q=%EC%9C%A0%EA%B8%B0%EA%B2%AC+%EC%9E%85%EC%96%91+%EB%B8%8C%EC%9D%B4%EB%A1%9C%EA%B7%B8",
            "order=relevance",
            "regionCode=KR",
            "safeSearch=strict",
            "videoEmbeddable=true",
            "publishedAfter=2023-11-11T00%3A00%3A00Z",
            "key=synthetic-key",
        )
        assertThat(searchQueries[1]).contains("pageToken=NEXT")
    }

    @Test
    fun `쪽 수를 다 채웠을 때__검색하면__다음 쪽 표시가 있어도 더 읽지 않는다`() {
        // given
        searchPages = mapOf("first" to "{\"nextPageToken\":\"NEXT\",\"items\":[{\"id\":{\"videoId\":\"AAAAAAAAAA1\"}}]}")

        // when
        val ids = client.search("유기묘 입양", Instant.parse("2023-11-11T00:00:00Z"), 1)

        // then
        assertThat(ids).containsExactly("AAAAAAAAAA1")
        assertThat(searchQueries).hasSize(1)
    }

    @Test
    fun `검색이 실패할 때__검색하면__키 없이 정제된 503을 돌려준다`() {
        // given
        status = 403
        searchPages = mapOf("first" to "synthetic-key should never escape")

        // when
        val thrown = assertThatThrownBy { client.search("유기묘 입양", Instant.parse("2023-11-11T00:00:00Z"), 1) }

        // then
        thrown
            .isInstanceOf(ResponseStatusException::class.java)
            .hasNoCause()
            .hasMessageNotContaining("synthetic-key")
    }

    @Test
    fun `상세가 있을 때__상세를 받으면__규칙에 쓸 칸을 채우고 숨긴 조회수는 0으로 둔다`() {
        // given
        payload = candidateJson(ID, "\"statistics\":{\"viewCount\":\"12345\"},") +
            "," + candidateJson("OtherVideo1", "")
        payload = "{\"items\":[" + payload + "]}"

        // when
        val videos = client.details(listOf(ID, "OtherVideo1"))

        // then
        assertThat(videos).hasSize(2)
        val first = videos.first { video -> video.videoId == ID }
        assertThat(first.channelId).isEqualTo("UCchannel000000000000001")
        assertThat(first.title).isEqualTo("입양 브이로그")
        assertThat(first.description).isEqualTo("첫 산책")
        assertThat(first.durationSeconds).isEqualTo(600)
        assertThat(first.viewCount).isEqualTo(12345)
        assertThat(first.publishedAt).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"))
        assertThat(first.playable).isTrue()
        assertThat(videos.first { video -> video.videoId == "OtherVideo1" }.viewCount).isZero()
        assertThat(query).contains("part=snippet%2CcontentDetails%2Cstatistics%2Cstatus", "key=synthetic-key")
    }

    @Test
    fun `채널이나 게시 시각이 없을 때__상세를 받으면__그 영상은 뺀다`() {
        // given
        val noChannel = candidateJson(ID, "").replace("\"channelId\":\"UCchannel000000000000001\",", "")
        val badDate = candidateJson("OtherVideo1", "").replace("2025-01-02T03:04:05Z", "어제")
        payload = "{\"items\":[$noChannel,$badDate]}"

        // when
        val videos = client.details(listOf(ID, "OtherVideo1"))

        // then
        assertThat(videos).isEmpty()
    }

    @Test
    fun `영상이 25개를 넘을 때__상세를 받으면__25개씩 나눠 묻는다`() {
        // given
        payload = "{\"items\":[]}"
        val ids = (1..30).map { number -> "Video" + number.toString().padStart(6, '0') }

        // when
        client.details(ids)

        // then
        assertThat(videoQueries).hasSize(2)
    }

    @Test
    fun `상세 공급자가 실패할 때__상세를 받으면__키 없이 정제된 503을 돌려준다`() {
        // given
        status = 500
        payload = "synthetic-key should never escape"

        // when
        val thrown = assertThatThrownBy { client.details(listOf(ID)) }

        // then
        thrown
            .isInstanceOf(ResponseStatusException::class.java)
            .hasNoCause()
            .hasMessageNotContaining("synthetic-key")
    }

    private companion object {
        const val ID = "AbCdEfGhI_1"

        fun candidateJson(id: String, statistics: String): String =
            """
            {"id":"$id","snippet":{"title":"입양 브이로그","description":"첫 산책","channelId":"UCchannel000000000000001",
            "channelTitle":"개인 채널","publishedAt":"2025-01-02T03:04:05Z","liveBroadcastContent":"none",
            "thumbnails":{"high":{"url":"https://i.ytimg.com/vi/$id/hqdefault.jpg"}}},
            "contentDetails":{"duration":"PT10M"},$statistics
            "status":{"privacyStatus":"public","uploadStatus":"processed","embeddable":true}}
            """.trimIndent()

        fun json(status: String, detail: String): String =
            """
            {"items":[{"id":"AbCdEfGhI_1","snippet":{"title":"일상","channelTitle":"테스트 채널",
            "liveBroadcastContent":"none","thumbnails":{"high":{"url":"https://i.ytimg.com/vi/AbCdEfGhI_1/hqdefault.jpg"}}},
            "contentDetails":{"duration":"PT3M24S"$detail},"status":{$status}}]}
            """.trimIndent() + "\n"
    }
}
