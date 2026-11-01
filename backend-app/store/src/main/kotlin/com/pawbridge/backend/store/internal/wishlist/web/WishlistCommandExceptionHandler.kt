package com.pawbridge.backend.store.internal.wishlist.web

import com.pawbridge.backend.store.internal.web.StoreErrorMessages
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [WishlistCommandController::class])
class WishlistCommandExceptionHandler {

    @JvmRecord
    data class FieldError(val field: String?, val value: String?, val reason: String?)

    @JvmRecord
    data class Error(val message: String?, val code: String?, val errors: List<FieldError>?)

    @ExceptionHandler(AuthenticationException::class)
    fun unauthenticated(failure: AuthenticationException): ResponseEntity<Void> =
        ResponseEntity.status(401).build()

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<Error> =
        ResponseEntity.badRequest().body(Error(StoreErrorMessages.INVALID_INPUT, "C001", listOf()))

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
    fun serverError(failure: Exception): ResponseEntity<Error> =
        ResponseEntity.internalServerError().body(Error(StoreErrorMessages.SERVER_ERROR, "C004", listOf()))
}
