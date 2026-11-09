package com.pawbridge.backend.community.internal.note.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.server.ResponseStatusException

class NoteStreamExceptionHandlerTest {
    @Test
    fun `이미 쓰기 시작한 스트림일 때__시간 초과로 끝나면__응답을 덧쓰지 않고 로그도 남기지 않는다`() {
        // given
        val response = MockHttpServletResponse()
        response.flushBuffer()

        // when
        val events = captureLogs {
            assertNull(NoteStreamExceptionHandler().streamTimedOut(MockHttpServletRequest(), response))
        }

        // then
        assertTrue(events.isEmpty())
    }

    @Test
    fun `쓰기 전인 스트림일 때__시간 초과로 끝나면__기존과 같은 503을 돌려주고 로그는 남기지 않는다`() {
        // given
        val response = MockHttpServletResponse()

        // when
        val events = captureLogs {
            val result = NoteStreamExceptionHandler().streamTimedOut(MockHttpServletRequest(), response)

            // then
            assertEquals(503, result!!.statusCode.value())
            assertEquals(mapOf("message" to "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."), result.body)
            assertEquals("no-store", response.getHeader("Cache-Control"))
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `이벤트 스트림만 받는 요청일 때__연결 한도로 거절하면__본문 없이 429와 재시도 시각을 준다`() {
        // given
        val request = MockHttpServletRequest()
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
        val response = MockHttpServletResponse()

        // when
        val result = NoteStreamExceptionHandler().status(connectionLimit(), request, response)

        // then
        assertEquals(429, result!!.statusCode.value())
        assertNull(result.body)
        assertEquals("15", result.headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertEquals("no-store", result.headers.cacheControl)
    }

    @Test
    fun `JSON을 받는 요청일 때__연결 한도로 거절하면__기존처럼 사유를 본문에 담는다`() {
        // given
        val request = MockHttpServletRequest()
        request.addHeader(HttpHeaders.ACCEPT, "*/*")

        // when
        val result = NoteStreamExceptionHandler().status(connectionLimit(), request, MockHttpServletResponse())

        // then
        assertEquals(429, result!!.statusCode.value())
        assertEquals(mapOf("message" to "알림 연결이 너무 많습니다."), result.body)
    }

    @Test
    fun `이벤트 스트림만 받는 요청일 때__형식 오류로 거절하면__본문 없이 400을 준다`() {
        // given
        val request = MockHttpServletRequest()
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
        val response = MockHttpServletResponse()

        // when
        val result = NoteStreamExceptionHandler().invalidRequest(request, response)

        // then
        assertEquals(400, result!!.statusCode.value())
        assertNull(result.body)
        assertEquals("no-store", response.getHeader("Cache-Control"))
    }

    @Test
    fun `Accept 헤더가 잘못됐을 때__거절하면__본문 없이 상태만 준다`() {
        // given
        val request = MockHttpServletRequest()
        request.addHeader(HttpHeaders.ACCEPT, "not a media type")

        // when
        val result = NoteStreamExceptionHandler().memberUnavailable(request, MockHttpServletResponse())

        // then
        assertEquals(503, result!!.statusCode.value())
        assertNull(result.body)
    }

    private fun connectionLimit(): ResponseStatusException =
        object : ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "알림 연결이 너무 많습니다.") {
            override fun getHeaders(): HttpHeaders = HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, "15") }
        }

    private fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(NoteStreamExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }
}
