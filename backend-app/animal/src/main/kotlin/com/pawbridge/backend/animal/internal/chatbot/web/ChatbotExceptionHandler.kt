package com.pawbridge.backend.animal.internal.chatbot.web

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotAnimalNotFoundException
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException
import com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation
import feign.FeignException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Original chatbot envelopes, scoped to this controller; logs only failure types on 500, never question/history/key. */
@RestControllerAdvice(assignableTypes = [ChatbotController::class])
class ChatbotExceptionHandler {

    @ExceptionHandler(ChatbotAnimalNotFoundException::class)
    fun animalMissing(failure: ChatbotAnimalNotFoundException): ResponseEntity<Failure> {
        return error(404, failure.message)
    }

    @ExceptionHandler(ChatbotRateLimitExceededException::class)
    fun quota(failure: ChatbotRateLimitExceededException): ResponseEntity<Failure> {
        return error(429, failure.message)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<Failure> {
        return error(400, failure.message)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(failure: MethodArgumentNotValidException): ResponseEntity<Failure> {
        val message = failure.bindingResult.fieldErrors
            .joinToString(", ") { field -> field.field + ": " + field.defaultMessage }
        return error(400, message)
    }

    @ExceptionHandler(BindException::class)
    fun binding(failure: BindException): ResponseEntity<Failure> {
        return error(400, failure.bindingResult.allErrors.first().defaultMessage)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(failure: HttpMessageNotReadableException): ResponseEntity<Failure> {
        return error(400, "요청 본문 형식이 올바르지 않습니다.")
    }

    @ExceptionHandler(DataAccessException::class)
    fun database(failure: DataAccessException): ResponseEntity<Failure> {
        if (PostgresqlRollbackCharsetViolation.matches(failure)) {
            return error(400, PostgresqlRollbackCharsetViolation.MESSAGE)
        }
        logFailure(failure)
        return error(500, "데이터베이스 오류가 발생했습니다.")
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception): ResponseEntity<Failure> {
        if (PostgresqlRollbackCharsetViolation.matches(failure)) {
            return error(400, PostgresqlRollbackCharsetViolation.MESSAGE)
        }
        logFailure(failure)
        return error(500, "서버 내부 오류가 발생했습니다.")
    }

    @JvmRecord
    data class Failure(
        val code: Int,
        val message: String?,
        val data: Any?,
    )

    private companion object {
        private val log = LoggerFactory.getLogger(ChatbotExceptionHandler::class.java)

        /**
         * 500으로 끝난 실패를 남긴다. 오류 메시지에는 SQL, 질문, 상류 응답이 섞일 수 있어 종류 이름만 남긴다.
         * Python 챗봇이 오류로 답한 경우(Feign)는 그 상태 코드를 같이 남긴다. 502는 Gemini 같은 상류 장애다.
         */
        private fun logFailure(failure: Exception) {
            var pythonStatus = "-"
            if (failure is FeignException && failure.status() > 0) {
                pythonStatus = failure.status().toString()
            }
            val cause = failure.cause?.javaClass?.simpleName ?: "-"
            log.error(
                "Chatbot request failed: error={} pythonStatus={} cause={}",
                failure.javaClass.simpleName,
                pythonStatus,
                cause,
            )
        }

        private fun error(code: Int, message: String?): ResponseEntity<Failure> {
            return ResponseEntity.status(code).body(Failure(code, message, null))
        }
    }
}
