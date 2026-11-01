package com.pawbridge.backend.store.internal.cart.web

import com.pawbridge.backend.store.internal.cart.application.CartActorRejectedException
import com.pawbridge.backend.store.internal.directorder.infrastructure.PostgresqlRollbackCharsetViolation
import com.pawbridge.backend.store.internal.web.StoreErrorMessages
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Scoped errors never log request, account, token, response, SQL or exception bodies. */
@RestControllerAdvice(assignableTypes = [CartController::class])
class CartExceptionHandler {

    @JvmRecord
    data class FieldError(val field: String?, val value: String?, val reason: String?)

    @JvmRecord
    data class Error(val message: String?, val code: String?, val errors: List<FieldError>?)

    @ExceptionHandler(AuthenticationException::class)
    fun unauthenticated(failure: AuthenticationException): ResponseEntity<Void> =
        ResponseEntity.status(401).build()

    @ExceptionHandler(CartActorRejectedException::class)
    fun actor(failure: CartActorRejectedException): ResponseEntity<Error> =
        ResponseEntity.status(403).body(Error(StoreErrorMessages.ACCESS_DENIED, "C006", listOf()))

    @ExceptionHandler(
        IllegalArgumentException::class,
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
    )
    fun invalid(failure: Exception): ResponseEntity<Error> =
        ResponseEntity.badRequest().body(Error(StoreErrorMessages.INVALID_INPUT, "C001", listOf()))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun type(failure: MethodArgumentTypeMismatchException): ResponseEntity<Error> =
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
