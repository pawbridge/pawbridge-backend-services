package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class TourApiClientTest {
    private val properties = properties()
    private val http: HttpClient = mockk<HttpClient>()
    private val client = TourApiClient(properties, ObjectMapper(), http)

    @Test
    fun `대량 반려동물 페이지를 받을 때__조회하면__콘텐츠 ID 없이 별도의 키와 서비스를 쓴다`() {
        // given
        properties.isBulkPetEnabled = true
        properties.koreanServiceKey = "koreanSynthetic1234%2B%2F%3D"
        val response = mockk<HttpResponse<ByteArray>>()

        every { response.statusCode() } returns 200
        every { response.body() } returns bulkPayload("[{\"contentid\":\"123\",\"acmpyNeedMtr\":\"목줄\"}]", 101)
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(response)

        // when
        val page = client.fetchPage(TourApiClient.Operation.PET_BULK, "", 2)

        // then
        val request = slot<HttpRequest>()
        assertThat(page.totalCount).isEqualTo(101)
        assertThat(page.items[0]).containsEntry("acmpyNeedMtr", "목줄")
        verify(exactly = 1) { http.sendAsync(capture(request), any<HttpResponse.BodyHandler<ByteArray>>()) }
        assertThat(request.captured.uri().path).isEqualTo("/B551011/KorService2/detailPetTour2")
        assertThat(request.captured.uri().rawQuery)
            .contains("pageNo=2", "numOfRows=100", "serviceKey=koreanSynthetic1234%2B%2F%3D")
            .doesNotContain("contentId", "syntheticOnly")
    }

    @Test
    fun `대량 수집이 꺼져 있거나 한국어 서비스 키가 없을 때__조회하면__반려동물 서비스 키로 대체하지 않는다`() {
        // given

        // when
        val disabled = catchThrowable { client.fetch(TourApiClient.Operation.PET_BULK, "") }
        properties.isBulkPetEnabled = true
        val missingKey = catchThrowable { client.fetch(TourApiClient.Operation.PET_BULK, "") }
        val common = catchThrowable { client.fetch(TourApiClient.Operation.COMMON, "123") }

        // then
        assertThat(disabled).hasMessage("UNAVAILABLE")
        assertThat(missingKey).hasMessage("UNAVAILABLE")
        assertThat(common).hasMessage("UNAVAILABLE")
        confirmVerified(http)
    }

    @Test
    fun `표시용 상세를 받을 때__조회하면__한국어 서비스와 그 인증키를 쓴다`() {
        // given
        properties.koreanServiceKey = "koreanSynthetic1234%2B%2F%3D"
        val response = mockk<HttpResponse<ByteArray>>()

        every { response.statusCode() } returns 200
        every { response.body() } returns payload("{\"contentid\":\"123\",\"title\":\"장소\"}")

        for (operation in listOf(TourApiClient.Operation.COMMON, TourApiClient.Operation.PET)) {
            clearMocks(http)
            every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                CompletableFuture.completedFuture(response)

            // when
            val rows = client.fetch(operation, "123")

            // then
            val request = slot<HttpRequest>()
            assertThat(rows).hasSize(1)
            verify(exactly = 1) { http.sendAsync(capture(request), any<HttpResponse.BodyHandler<ByteArray>>()) }
            assertThat(request.captured.uri().path).isEqualTo("/B551011/KorService2/" + operation.path)
            assertThat(request.captured.uri().rawQuery)
                .contains("contentId=123", "serviceKey=koreanSynthetic1234%2B%2F%3D")
                .doesNotContain("syntheticOnly")
        }
    }

    @Test
    fun `방문 자원을 받을 때__조회하면__필요한 곳에만 콘텐츠 유형을 보내고 필드를 유지한다`() {
        // given
        properties.koreanServiceKey = "koreanSynthetic1234%2B%2F%3D"
        val intro = mockk<HttpResponse<ByteArray>>()
        val info = mockk<HttpResponse<ByteArray>>()
        val images = mockk<HttpResponse<ByteArray>>()

        every { intro.statusCode() } returns 200
        every { intro.body() } returns bulkPayload(
            "[{\"contentid\":\"123\",\"contenttypeid\":\"12\",\"usetime\":\"09:00~18:00\"," +
                "\"expguide\":\"해설 프로그램\",\"useseason\":\"연중\"}]",
            1,
        )
        every { info.statusCode() } returns 200
        every { info.body() } returns bulkPayload(
            "[{\"contentid\":\"123\",\"contenttypeid\":\"12\",\"serialnum\":\"1\",\"infoname\":\"안내\",\"infotext\":\"설명\"}]",
            1,
        )
        every { images.statusCode() } returns 200
        every { images.body() } returns bulkPayload(
            "[{\"contentid\":\"123\",\"serialnum\":\"1\",\"originimgurl\":\"https://tong.visitkorea.or.kr/cms/resource/1/a.jpg\",\"cpyrhtDivCd\":\"Type1\"}]",
            1,
        )

        // when
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(intro)
        val introRows = client.fetchDetail(TourApiClient.Operation.INTRO, "123", "12")
        val introRequest = slot<HttpRequest>()
        verify(exactly = 1) { http.sendAsync(capture(introRequest), any<HttpResponse.BodyHandler<ByteArray>>()) }
        clearMocks(http)

        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(info)
        val infoRows = client.fetchDetail(TourApiClient.Operation.INFO, "123", "12")
        clearMocks(http)

        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(images)
        val imageRows = client.fetchDetail(TourApiClient.Operation.IMAGES, "123", "12")

        // then
        val imageRequest = slot<HttpRequest>()
        assertThat(introRows[0])
            .containsEntry("usetime", "09:00~18:00")
            .containsEntry("expguide", "해설 프로그램")
            .containsEntry("useseason", "연중")
        assertThat(introRequest.captured.uri().path).endsWith("/detailIntro2")
        assertThat(introRequest.captured.uri().rawQuery).contains("contentId=123", "contentTypeId=12", "numOfRows=1")
        assertThat(infoRows[0]).containsEntry("infoname", "안내")
        assertThat(imageRows[0]).containsEntry("serialnum", "1")
        verify(exactly = 1) { http.sendAsync(capture(imageRequest), any<HttpResponse.BodyHandler<ByteArray>>()) }
        assertThat(imageRequest.captured.uri().path).endsWith("/detailImage2")
        assertThat(imageRequest.captured.uri().rawQuery).contains("contentId=123", "imageYN=Y", "numOfRows=100")
            .doesNotContain("contentTypeId")
    }

    @Test
    fun `방문 자원이 한 페이지를 넘을 때__조회하면__부분 스냅샷을 거부한다`() {
        // given
        properties.koreanServiceKey = KEY
        val response = mockk<HttpResponse<ByteArray>>()

        every { response.statusCode() } returns 200
        every { response.body() } returns
            bulkPayload("[{\"contentid\":\"123\",\"contenttypeid\":\"12\",\"serialnum\":\"1\",\"infoname\":\"안내\"}]", 101)
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(response)

        // when
        val failure = catchThrowable { client.fetchDetail(TourApiClient.Operation.INFO, "123", "12") }

        // then
        assertThat(failure).hasMessage("UNAVAILABLE")
    }

    @Test
    fun `방문 자원에 행이 없을 때__조회하면__성공한 빈 스냅샷으로 받아들인다`() {
        // given
        properties.koreanServiceKey = KEY
        val response = mockk<HttpResponse<ByteArray>>()

        every { response.statusCode() } returns 200
        every { response.body() } returns
            (
                "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":0," +
                    "\"items\":\"\"}}}"
                ).toByteArray(StandardCharsets.UTF_8)
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(response)

        // when
        val rows = client.fetchDetail(TourApiClient.Operation.INTRO, "123", "28")

        // then
        assertThat(rows).isEmpty()
    }

    @Test
    fun `대량 응답에 같은 식별자가 중복될 때__조회하면__페이지를 거부한다`() {
        // given
        properties.isBulkPetEnabled = true
        properties.bulkServiceKey = KEY
        val response = mockk<HttpResponse<ByteArray>>()

        every { response.statusCode() } returns 200
        every { response.body() } returns bulkPayload("[{\"contentid\":\"123\"},{\"contentid\":\"123\"}]", 2)
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(response)

        // when
        val failure = catchThrowable { client.fetch(TourApiClient.Operation.PET_BULK, "") }

        // then
        assertThat(failure)
            .hasMessage("UNAVAILABLE")
            .hasNoCause()
    }

    @Test
    fun `대량 응답에 전체 건수가 없을 때__조회하면__페이지 크기로 완료를 추정하지 않는다`() {
        // given
        properties.isBulkPetEnabled = true
        properties.bulkServiceKey = KEY
        val response = mockk<HttpResponse<ByteArray>>()

        every { response.statusCode() } returns 200
        every { response.body() } returns payload("{\"contentid\":\"123\"}")
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(response)

        // when
        val failure = catchThrowable { client.fetch(TourApiClient.Operation.PET_BULK, "") }

        // then
        assertThat(failure).hasMessage("UNAVAILABLE")
    }

    @Test
    fun `숨김 동기화 페이지를 받을 때__조회하면__메타데이터와 명시적 페이징을 유지한다`() {
        // given
        val response = mockk<HttpResponse<ByteArray>>()

        every { response.statusCode() } returns 200
        every { response.body() } returns
            (
                "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":101," +
                    "\"items\":{\"item\":[{\"contentid\":\"123\",\"showflag\":\"0\",\"lDongRegnCd\":\"36110\"," +
                    "\"modifiedtime\":\"20260912000000\"}]}}}}"
                ).toByteArray(StandardCharsets.UTF_8)
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(response)

        // when
        val page = client.fetchPage(TourApiClient.Operation.SYNC, "0", 2)

        // then
        val request = slot<HttpRequest>()
        assertThat(page.totalCount).isEqualTo(101)
        assertThat(page.items[0]).containsEntry("showflag", "0").containsEntry("lDongRegnCd", "36110")
            .containsEntry("modifiedtime", "20260912000000")
        verify(exactly = 1) { http.sendAsync(capture(request), any<HttpResponse.BodyHandler<ByteArray>>()) }
        assertThat(request.captured.uri().path).endsWith("/petTourSyncList2")
        assertThat(request.captured.uri().rawQuery).contains("pageNo=2", "numOfRows=100", "showflag=0")
    }

    @Test
    fun `페이징 값이 올바르지 않을 때__조회하면__제공사를 호출하지 않는다`() {
        // given

        // when
        val zeroPage = catchThrowable { client.fetchPage(TourApiClient.Operation.SYNC, "1", 0) }
        val badFlag = catchThrowable { client.fetchPage(TourApiClient.Operation.SYNC, "all", 1) }

        // then
        assertThat(zeroPage).isInstanceOf(PetTravelException::class.java)
        assertThat(badFlag).isInstanceOf(PetTravelException::class.java)
        confirmVerified(http)
    }

    @Test
    fun `이미 인코딩된 키가 있을 때__조회하면__한 번만 인코딩하고 고정 호스트로만 보낸다`() {
        // given
        properties.serviceKey = "syntheticOnly1234%2B%2F%3D"
        val response = mockk<HttpResponse<ByteArray>>()

        every { response.statusCode() } returns 200
        every { response.body() } returns payload("{\"code\":\"36110\",\"name\":\"서울\"}")
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.completedFuture(response)

        // when
        val rows = client.fetch(TourApiClient.Operation.REGIONS, "")

        // then
        val request = slot<HttpRequest>()
        assertThat(rows).hasSize(1)
        verify(exactly = 1) { http.sendAsync(capture(request), any<HttpResponse.BodyHandler<ByteArray>>()) }
        assertThat(request.captured.uri().host).isEqualTo("apis.data.go.kr")
        assertThat(request.captured.uri().path).isEqualTo("/B551011/KorPetTourService2/ldongCode2")
        assertThat(request.captured.uri().rawQuery).contains("serviceKey=syntheticOnly1234%2B%2F%3D")
        assertThat(request.captured.timeout()).contains(Duration.ofSeconds(5))
    }

    @Test
    fun `키가 비어 있을 때__조회하면__HTTP 요청을 보내지 않는다`() {
        // given
        properties.serviceKey = ""

        // when
        val failure = catchThrowable { client.fetch(TourApiClient.Operation.REGIONS, "") }

        // then
        assertThat(failure).isInstanceOf(PetTravelException::class.java)
        confirmVerified(http)
    }

    @Test
    fun `오류나 리다이렉트 응답을 받을 때__조회하면__재시도 없이 안전한 예외를 던진다`() {
        // given
        for (status in intArrayOf(302, 429, 500)) {
            val response = mockk<HttpResponse<ByteArray>>()

            clearMocks(http)
            every { response.statusCode() } returns status
            every { response.body() } returns KEY.toByteArray(StandardCharsets.UTF_8)
            every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                CompletableFuture.completedFuture(response)

            // when
            val failure = catchThrowable { client.fetch(TourApiClient.Operation.REGIONS, "") }

            // then
            assertThat(failure)
                .isInstanceOf(PetTravelException::class.java).hasMessage("UNAVAILABLE").hasNoCause()
            verify(exactly = 1) { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) }
        }
    }

    @Test
    fun `예외에 비밀이 담겨 있을 때__조회하면__원인을 제거한다`() {
        // given
        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
            CompletableFuture.failedFuture<HttpResponse<ByteArray>>(IllegalStateException(KEY))

        // when
        val failure = catchThrowable { client.fetch(TourApiClient.Operation.REGIONS, "") }

        // then
        assertThat(failure)
            .hasMessage("UNAVAILABLE").hasNoCause()
    }

    @Test
    fun `응답이 시간 초과될 때__조회하면__대기 중인 요청을 취소한다`() {
        // given
        val future = mockk<CompletableFuture<HttpResponse<ByteArray>>>()

        every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns future
        every { future.get(6, TimeUnit.SECONDS) } throws TimeoutException(KEY)
        every { future.isDone } returns false
        every { future.cancel(true) } returns true

        // when
        val failure = catchThrowable { client.fetch(TourApiClient.Operation.REGIONS, "") }

        // then
        assertThat(failure).hasMessage("UNAVAILABLE")
        assertThat((failure as PetTravelException).reason).isEqualTo(TourApiClient.RESPONSE_WAIT_TIMEOUT)
        verify(exactly = 1) { future.cancel(true) }
    }

    @Test
    fun `응답 상태나 본문이 실패일 때__조회하면__키 없는 실패 종류와 호출 이름과 상태를 예외에 담는다`() {
        // given
        val cases = listOf(
            Triple(503, KEY, TourApiClient.HTTP_STATUS),
            Triple(
                200,
                "<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>" +
                    "<returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>",
                "XML_ERROR_22",
            ),
            Triple(200, "\uFEFF  <html>" + KEY + "</html>", TourApiClient.XML_ERROR),
            Triple(200, "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"" + KEY + "\"}}}", "RESULT_CODE_30"),
            Triple(200, "{\"response\":{\"header\":{\"resultCode\":\"" + KEY + "\"}}}", TourApiClient.RESULT_CODE),
            Triple(200, "not-json " + KEY, TourApiClient.INVALID_JSON),
            Triple(200, "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":[]}}", TourApiClient.INVALID_BODY),
        )

        for ((status, body, kind) in cases) {
            val response = mockk<HttpResponse<ByteArray>>()

            clearMocks(http)
            every { response.statusCode() } returns status
            every { response.body() } returns body.toByteArray(StandardCharsets.UTF_8)
            every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                CompletableFuture.completedFuture(response)

            // when
            val failure = catchThrowable { client.fetch(TourApiClient.Operation.REGIONS, "") }

            // then
            assertThat(failure).isInstanceOf(PetTravelException::class.java).hasMessage("UNAVAILABLE").hasNoCause()
            val travel = failure as PetTravelException
            assertThat(travel.reason).describedAs(body).isEqualTo(kind)
            assertThat(travel.reason).doesNotContain(KEY)
            assertThat(travel.operation).isEqualTo("REGIONS")
            assertThat(travel.status).isEqualTo(status)
        }
    }

    @Test
    fun `전송이 실패할 때__조회하면__원인 클래스로 실패 종류를 나누고 원인은 버린다`() {
        // given
        val cases = listOf(
            HttpConnectTimeoutException("https://apis.data.go.kr/x?serviceKey=" + KEY) to TourApiClient.CONNECT_TIMEOUT,
            HttpTimeoutException("https://apis.data.go.kr/x?serviceKey=" + KEY) to TourApiClient.REQUEST_TIMEOUT,
            ConnectException(KEY) to TourApiClient.CONNECT,
            IOException(KEY) to TourApiClient.IO,
            IllegalStateException(KEY) to TourApiClient.OTHER,
        )

        for ((cause, kind) in cases) {
            clearMocks(http)
            every { http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                CompletableFuture.failedFuture<HttpResponse<ByteArray>>(cause)

            // when
            val failure = catchThrowable { client.fetch(TourApiClient.Operation.SYNC, "1") }

            // then
            assertThat(failure).hasMessage("UNAVAILABLE").hasNoCause()
            val travel = failure as PetTravelException
            assertThat(travel.reason).isEqualTo(kind)
            assertThat(travel.operation).isEqualTo("SYNC")
            assertThat(travel.status).isNull()
        }
    }

    @Test
    fun `잘못된 본문이나 제공사 오류를 받을 때__파싱하면__안전하게 실패한다`() {
        // given
        val bodies = listOf(
            KEY,
            "{}",
            "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"" + KEY + "\"}}}",
        )

        for (body in bodies) {
            // when
            val failure = catchThrowable {
                client.parse(body.toByteArray(StandardCharsets.UTF_8), TourApiClient.Operation.REGIONS, KEY)
            }

            // then
            assertThat(failure).hasMessage("UNAVAILABLE").hasNoCause()
        }
    }

    @Test
    fun `반려동물 응답이 단일 객체일 때__파싱하면__허용 필드의 순수 텍스트만 남기고 키를 가린다`() {
        // given
        val body = payload(
            "{\"contentid\":\"123\",\"acmpyPsblCpam\":\"<b>안내견</b>\",\"etcAcmpyInfo\":\"" + KEY +
                "\",\"unknown\":\"private\"}",
        )

        // when
        val data = client.parse(body, TourApiClient.Operation.PET, KEY)

        // then
        assertThat(data[0]).containsEntry("acmpyPsblCpam", "안내견")
            .containsEntry("etcAcmpyInfo", "[REDACTED]").doesNotContainKey("unknown")
    }

    @Test
    fun `항목이 비어 있을 때__파싱하면__빈 결과를 돌려준다`() {
        // given
        val emptyArray = payload("[]")
        val emptyString = "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":\"\"}}}"
            .toByteArray(StandardCharsets.UTF_8)

        // when
        val fromArray = client.parse(emptyArray, TourApiClient.Operation.PLACES, KEY)
        val fromString = client.parse(emptyString, TourApiClient.Operation.PET, KEY)

        // then
        assertThat(fromArray).isEmpty()
        assertThat(fromString).isEmpty()
    }

    @Test
    fun `본문이 한도를 넘을 때__소비하면__쌓기 전에 구독을 취소한다`() {
        // given
        val subscriber = TourApiClient.LimitedBody()
        val subscription = mockk<Flow.Subscription>()

        justRun { subscription.request(any()) }
        justRun { subscription.cancel() }

        // when
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.allocate(TourApiClient.MAX_BYTES + 1)))

        // then
        verify(exactly = 1) { subscription.cancel() }
        assertThat(catchThrowable { subscriber.getBody().toCompletableFuture().join() })
            .hasCauseInstanceOf(PetTravelException::class.java)
    }

    @Test
    fun `요청한 행 수보다 많이 올 때__파싱하면__무제한 목록 대신 거부한다`() {
        // given
        val row = "{\"contentid\":\"123\",\"title\":\"장소\"}"
        val rows = "[" + Collections.nCopies(11, row).joinToString(",") + "]"

        // when
        val failure = catchThrowable { client.parse(payload(rows), TourApiClient.Operation.PLACES, KEY) }

        // then
        assertThat(failure).hasMessage("UNAVAILABLE")
    }

    @Test
    fun `대표 사진이 있을 때__파싱하면__URL과 그 사진의 저작권 유형을 유지한다`() {
        // given
        val body = payload(
            "{\"contentid\":\"123\",\"title\":\"장소\"," +
                "\"firstimage\":\"http://tong.visitkorea.or.kr/cms/resource/1/photo.jpg\",\"cpyrhtDivCd\":\"Type1\"}",
        )

        // when
        val data = client.parse(body, TourApiClient.Operation.PLACES, KEY)

        // then
        assertThat(data[0]).containsEntry("firstimage", "http://tong.visitkorea.or.kr/cms/resource/1/photo.jpg")
            .containsEntry("cpyrhtDivCd", "Type1")
    }

    @Test
    fun `무게 비교 기호가 있을 때__파싱하면__제한 조건을 그대로 보존한다`() {
        // given
        val body = payload("{\"contentid\":\"123\",\"acmpyPsblCpam\":\"<10kg, >3kg\"}")

        // when
        val data = client.parse(body, TourApiClient.Operation.PET, KEY)

        // then
        assertThat(data[0]["acmpyPsblCpam"]).isEqualTo("<10kg, >3kg")
    }

    @Test
    fun `조건 문자열이 너무 길 때__파싱하면__자르지 않고 거부한다`() {
        // given
        val body = payload("{\"contentid\":\"123\",\"acmpyNeedMtr\":\"" + "x".repeat(10001) + "\"}")

        // when
        val failure = catchThrowable { client.parse(body, TourApiClient.Operation.PET, KEY) }

        // then
        assertThat(failure).hasMessage("UNAVAILABLE")
    }

    companion object {
        private const val KEY = "syntheticOnly1234+/="

        private fun bulkPayload(rows: String, total: Int): ByteArray {
            return (
                "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":" + total +
                    ",\"items\":{\"item\":" + rows + "}}}}"
                ).toByteArray(StandardCharsets.UTF_8)
        }

        private fun payload(rows: String): ByteArray {
            return (
                "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":" + rows + "}}}}"
                ).toByteArray(StandardCharsets.UTF_8)
        }

        private fun properties(): TourApiProperties {
            val properties = TourApiProperties()
            properties.isEnabled = true
            properties.serviceKey = KEY
            return properties
        }
    }
}
