package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.NoteStreamRejectedException
import com.pawbridge.backend.community.internal.note.application.NoteStreamRejectedException.Reason
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [NoteStreamController::class])
class NoteStreamExceptionHandler {

    @ExceptionHandler(NoteStreamRejectedException::class)
    fun rejected(
        failure: NoteStreamRejectedException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, String>>? =
        when (failure.reason()) {
            Reason.LOGIN -> error(401, "로그인이 필요합니다.", request, response)
            Reason.INACTIVE -> error(403, "이용할 수 없는 회원입니다.", request, response)
        }

    @ExceptionHandler(ResponseStatusException::class)
    fun status(
        failure: ResponseStatusException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, String>>? {
        if (response.isCommitted) {
            return null
        }
        response.setHeader("Cache-Control", "no-store")
        val reply = ResponseEntity.status(failure.statusCode).headers(failure.headers)
            .cacheControl(CacheControl.noStore())
        if (!acceptsJson(request)) {
            return reply.build()
        }
        return reply.body(mapOf("message" to (failure.reason ?: "요청을 처리할 수 없습니다.")))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidRequest(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Map<String, String>>? =
        error(400, "요청 형식과 로그인 정보를 확인해 주세요.", request, response)

    @ExceptionHandler(ContactMemberUnavailableException::class)
    fun memberUnavailable(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Map<String, String>>? =
        error(503, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", request, response)

    @ExceptionHandler(DataAccessException::class)
    fun storageUnavailable(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Map<String, String>>? =
        error(503, "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.", request, response)

    // An open stream ends with this when its emitter times out and the client reconnects, so it is not a failure
    // and is not logged. The response stays the same as before for a stream that has not started writing.
    @ExceptionHandler(AsyncRequestTimeoutException::class)
    fun streamTimedOut(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Map<String, String>>? =
        error(503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", request, response)

    @ExceptionHandler(RuntimeException::class)
    fun unexpectedFailure(
        failure: RuntimeException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, String>>? {
        log.warn("Private note request failed ({})", failure.javaClass.simpleName)
        return error(503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", request, response)
    }

    private fun error(
        code: Int,
        message: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, String>>? {
        if (response.isCommitted) {
            return null
        }
        response.setHeader("Cache-Control", "no-store")
        val reply = ResponseEntity.status(code).cacheControl(CacheControl.noStore())
        if (!acceptsJson(request)) {
            return reply.build()
        }
        return reply.body(mapOf("message" to message))
    }

    // Accept: text/event-stream만 보내는 스트림 요청은 JSON 본문을 받을 수 없다.
    // 본문을 주면 Spring이 쓰지 못해 처리기 실패 WARN을 남기고, 기본 처리기가 같은 상태를 빈 본문으로 다시 보낸다.
    // 그래서 처음부터 상태와 헤더만 준다.
    private fun acceptsJson(request: HttpServletRequest): Boolean {
        val accept = request.getHeaders(HttpHeaders.ACCEPT)?.toList().orEmpty().joinToString(",")
        if (accept.isBlank()) {
            return true
        }
        return try {
            MediaType.parseMediaTypes(accept).any { type -> type.isCompatibleWith(MediaType.APPLICATION_JSON) }
        } catch (invalid: InvalidMediaTypeException) {
            false
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(NoteStreamExceptionHandler::class.java)
    }
}
