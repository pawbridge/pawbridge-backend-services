package com.pawbridge.backend.store.internal.catalog.web

import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException
import com.pawbridge.backend.store.internal.web.StoreErrorMessages
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes = [StoreCatalogController::class])
class StoreCatalogExceptionHandler {

    @JvmRecord
    data class FieldError(val field: String?, val value: String?, val reason: String?)

    @JvmRecord
    data class Error(val message: String?, val code: String?, val errors: List<FieldError>?)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<Error> =
        ResponseEntity.badRequest().body(Error(StoreErrorMessages.INVALID_INPUT, "C001", listOf()))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun wrongType(failure: MethodArgumentTypeMismatchException): ResponseEntity<Error> =
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

    @ExceptionHandler(CatalogSearchUnavailableException::class)
    fun searchUnavailable(failure: CatalogSearchUnavailableException): ResponseEntity<Error> =
        ResponseEntity.status(503).body(Error(failure.message, "P006", listOf()))

    @ExceptionHandler(Exception::class)
    fun serverError(failure: Exception): ResponseEntity<Error> =
        ResponseEntity.internalServerError().body(Error(StoreErrorMessages.SERVER_ERROR, "C004", listOf()))
}
