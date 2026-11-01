package com.pawbridge.backend.store.internal.directorder.web

import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.pawbridge.backend.store.internal.directorder.infrastructure.PostgresqlRollbackCharsetViolation
import com.pawbridge.backend.store.internal.web.StoreErrorMessages
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** No request, account, shipping, token, response or exception-body logging. */
@RestControllerAdvice(
    assignableTypes = [
        DirectOrderController::class,
        OrderReadController::class,
        CartOrderController::class,
    ],
)
class DirectOrderExceptionHandler {

    @JvmRecord
    data class FieldError(val field: String?, val value: String?, val reason: String?)

    @JvmRecord
    data class Error(val message: String?, val code: String?, val errors: List<FieldError>?)

    @ExceptionHandler(AuthenticationException::class)
    fun unauthenticated(failure: AuthenticationException): ResponseEntity<Void> =
        ResponseEntity.status(401).build()

    @ExceptionHandler(OrderActorRejectedException::class)
    fun actorRejected(failure: OrderActorRejectedException): ResponseEntity<Error> =
        ResponseEntity.status(403).body(Error(StoreErrorMessages.ACCESS_DENIED, "C006", listOf()))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<Error> =
        ResponseEntity.badRequest().body(Error(StoreErrorMessages.INVALID_INPUT, "C001", listOf()))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(failure: MethodArgumentNotValidException): ResponseEntity<Error> =
        ResponseEntity.badRequest().body(
            Error(
                StoreErrorMessages.INVALID_INPUT,
                "C001",
                failure.bindingResult.fieldErrors.map { error ->
                    FieldError(
                        error.field,
                        if (error.rejectedValue == null) "" else error.rejectedValue.toString(),
                        error.defaultMessage,
                    )
                },
            ),
        )

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(failure: MethodArgumentTypeMismatchException): ResponseEntity<Error> =
        ResponseEntity.badRequest().body(
            Error(
                StoreErrorMessages.INVALID_TYPE,
                "C005",
                listOf(
                    FieldError(
                        failure.name,
                        if (failure.value == null) "" else failure.value.toString(),
                        failure.errorCode,
                    ),
                ),
            ),
        )

    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception): ResponseEntity<Error> {
        if (PostgresqlRollbackCharsetViolation.matches(failure)) {
            return ResponseEntity.badRequest()
                .body(Error(PostgresqlRollbackCharsetViolation.MESSAGE, "C007", listOf()))
        }
        return ResponseEntity.internalServerError().body(Error(StoreErrorMessages.SERVER_ERROR, "C004", listOf()))
    }
}
