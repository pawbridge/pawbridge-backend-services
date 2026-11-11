package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.AnimalChatbotContext
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ShelterChatbotContext
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotRecentMessage
import feign.Client
import feign.FeignException
import feign.Request
import feign.Response
import feign.RetryableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FeignPythonChatbotClientTest {
    private val json = ObjectMapper()
    private val input = PythonChatbotMessageRequest(
        AnimalChatbotContext("DOG", "mix", "2023년생", "5(Kg)", "white", null, null, "calm", "보호중"),
        ShelterChatbotContext("가상 보호소", "000-0000-0000", "가상시 가상구 1", "2026-11-20", null, null),
        listOf(ChatbotRecentMessage("user", "입양 문의"), ChatbotRecentMessage("assistant", "저장된 답변")),
        "마스킹된 문의",
    )

    @Test
    fun `건강 해설 출처와 카드가 있는 응답일 때__호출하면__두 목록을 해석한다`() {
        // given
        val transport = Client { request, _ ->
            response(
                request,
                200,
                "{\"answer\":\"답변 [1]\",\"safetyNotice\":\"주의\",\"provider\":\"gemini\"," +
                    "\"sources\":[{\"org\":\"기관\",\"title\":\"문서\",\"url\":\"https://example.org/a\"," +
                    "\"checkedAt\":\"2026-11-06\",\"language\":\"ko\"}]," +
                    "\"healthCards\":[{\"id\":\"heartworm-positive\",\"title\":\"심장사상충 양성\"," +
                    "\"summary\":[\"요약\"],\"cautions\":[\"주의\"],\"askShelter\":[\"치료 단계\"]}]}",
            )
        }
        val client = FeignPythonChatbotClient("http://python.example.invalid", json, transport, Request.Options())

        // when
        val result = client.createChatbotMessage("synthetic-key", input)!!

        // then
        assertEquals("https://example.org/a", result.sources!!.single().url)
        assertEquals("heartworm-positive", result.healthCards!!.single().id)
        assertEquals(listOf("치료 단계"), result.healthCards!!.single().askShelter)
    }

    @Test
    fun `건강 해설 칸이 없는 이전 응답일 때__호출하면__두 목록은 비어 있다`() {
        // given
        val transport = Client { request, _ ->
            response(request, 200, "{\"answer\":\"답변\",\"safetyNotice\":\"주의\",\"provider\":\"stub\"}")
        }
        val client = FeignPythonChatbotClient("http://python.example.invalid", json, transport, Request.Options())

        // when
        val result = client.createChatbotMessage("synthetic-key", input)!!

        // then
        assertEquals(null, result.sources)
        assertEquals(null, result.healthCards)
    }

    @Test
    fun `명시한 전송 옵션과 키와 원래 DTO가 있을 때__호출하면__정확한 경로와 헤더와 본문을 보내고 응답 필드 세 개를 해석한다`() {
        // given
        val captured = AtomicReference<Request>()
        val observedOptions = AtomicReference<Request.Options>()
        val options = Request.Options()
        val transport = Client { request, current ->
            captured.set(request)
            observedOptions.set(current)
            response(request, 200, "{\"answer\":\"답변\",\"safetyNotice\":\"주의\",\"provider\":\"fixture\"}")
        }
        val client = FeignPythonChatbotClient("http://python.example.invalid:8000", json, transport, options)

        // when
        val result = client.createChatbotMessage("synthetic-internal-key", input)!!

        // then
        assertEquals("답변", result.answer)
        assertEquals("주의", result.safetyNotice)
        assertEquals("fixture", result.provider)
        val request = captured.get()
        assertEquals(Request.HttpMethod.POST, request.httpMethod())
        assertEquals("http://python.example.invalid:8000/internal/chatbot/messages", request.url())
        assertEquals(listOf("synthetic-internal-key"), request.headers()["X-Internal-Api-Key"]!!.toList())
        val body = json.readTree(request.body())
        assertEquals(4, body.size())
        assertEquals(9, body.get("animalContext").size())
        // Python ShelterContext와 같은 키 6개. 빈 칸도 null로 보낸다.
        assertEquals(
            listOf("shelterName", "shelterPhone", "shelterAddress", "noticeEndDate", "adoptionProcedure", "operatingHours"),
            body.get("shelterContext").fieldNames().asSequence().toList(),
        )
        assertTrue(body.get("shelterContext").get("adoptionProcedure").isNull)
        assertFalse(body.get("animalContext").has("animalId"))
        assertEquals(json.readTree(json.writeValueAsBytes(input)), body)
        assertEquals(2, body.get("recentMessages").get(0).size())
        assertSame(options, observedOptions.get())
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 429, 500])
    fun `상류가 HTTP 오류를 돌려줄 때__호출하면__로컬 한도로 바꾸거나 재시도하지 않고 Feign 실패를 그대로 전파한다`(
        status: Int,
    ) {
        // given
        val calls = AtomicInteger()
        val transport = Client { request, _ ->
            calls.incrementAndGet()
            response(request, status, "private upstream detail")
        }
        val client = FeignPythonChatbotClient("http://python.example.invalid", json, transport, Request.Options())

        // when
        val thrown = assertThrows(FeignException::class.java) { client.createChatbotMessage("synthetic-key", input) }

        // then
        assertEquals(status, thrown.status())
        assertEquals(1, calls.get())
    }

    @Test
    fun `전송 계층이 입출력 오류를 낼 때__호출하면__재시도하거나 답변을 꾸며내지 않는다`() {
        // given
        val calls = AtomicInteger()
        val transport = Client { _, _ ->
            calls.incrementAndGet()
            throw IOException("synthetic network failure")
        }
        val client = FeignPythonChatbotClient("http://python.example.invalid", json, transport, Request.Options())

        // when
        assertThrows(RetryableException::class.java) { client.createChatbotMessage("synthetic-key", input) }

        // then
        assertEquals(1, calls.get())
    }

    @Test
    fun `성공 응답의 JSON이 깨졌을 때__호출하면__답변 없이 해석 실패를 전파한다`() {
        // given
        val transport = Client { request, _ -> response(request, 200, "{broken") }
        val client = FeignPythonChatbotClient("http://python.example.invalid", json, transport, Request.Options())

        // when
        assertThrows(FeignException::class.java) { client.createChatbotMessage("synthetic-key", input) }

        // then
    }

    @Test
    fun `제공자 응답 필드가 널일 때__호출하면__저장을 위해 원래의 검증되지 않은 값을 그대로 둔다`() {
        // given
        val transport = Client { request, _ ->
            response(request, 200, "{\"answer\":null,\"safetyNotice\":null,\"provider\":null}")
        }
        val client = FeignPythonChatbotClient("http://python.example.invalid", json, transport, Request.Options())

        // when
        val result = client.createChatbotMessage("synthetic-key", input)!!

        // then
        assertNull(result.answer)
        assertNull(result.safetyNotice)
        assertNull(result.provider)
    }

    private fun response(request: Request, status: Int, body: String): Response {
        return Response.builder()
            .request(request)
            .status(status)
            .reason("fixture")
            .headers(mapOf("Content-Type" to listOf("application/json")))
            .body(body, StandardCharsets.UTF_8)
            .build()
    }
}
