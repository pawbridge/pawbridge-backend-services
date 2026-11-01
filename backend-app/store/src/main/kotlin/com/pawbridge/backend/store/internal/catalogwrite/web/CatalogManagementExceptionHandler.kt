package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.directorder.infrastructure.PostgresqlRollbackCharsetViolation
import com.pawbridge.backend.store.internal.web.StoreErrorMessages
import org.springframework.http.ResponseEntity
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingPathVariableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Original Store error envelope/statuses, scoped to the added handlers and without payload logging. */
@RestControllerAdvice(
    assignableTypes = [
        ProductWriteController::class,
        OptionManagementController::class,
        ProductImageController::class,
        CategoryManagementController::class,
    ],
)
class CatalogManagementExceptionHandler {

    @JvmRecord
    data class FieldError(val field: String?, val value: String?, val reason: String?)

    @JvmRecord
    data class Error(val message: String?, val code: String?, val errors: List<FieldError>?)

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

    @ExceptionHandler(MissingPathVariableException::class)
    fun missingPathVariable(failure: MissingPathVariableException): ResponseEntity<Error> =
        ResponseEntity.badRequest().body(
            Error(
                StoreErrorMessages.INVALID_TYPE,
                "C005",
                // A blank segment is converted to null, so the value is empty like the type-mismatch body.
                listOf(FieldError(failure.variableName, "", "typeMismatch")),
            ),
        )

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(failure: HttpRequestMethodNotSupportedException): ResponseEntity<Error> =
        ResponseEntity.status(405).body(Error(StoreErrorMessages.METHOD_NOT_ALLOWED, "C002", listOf()))

    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception): ResponseEntity<Error> {
        if (PostgresqlRollbackCharsetViolation.matches(failure)) {
            return ResponseEntity.badRequest()
                .body(Error(PostgresqlRollbackCharsetViolation.MESSAGE, "C007", listOf()))
        }
        return ResponseEntity.internalServerError().body(Error(StoreErrorMessages.SERVER_ERROR, "C004", listOf()))
    }
}
