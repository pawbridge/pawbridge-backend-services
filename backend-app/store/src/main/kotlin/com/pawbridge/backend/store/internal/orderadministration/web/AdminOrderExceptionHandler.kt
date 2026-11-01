package com.pawbridge.backend.store.internal.orderadministration.web

import com.pawbridge.backend.store.internal.directorder.infrastructure.PostgresqlRollbackCharsetViolation
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrderNotFoundException
import com.pawbridge.backend.store.internal.orderadministration.application.AdministratorRejectedException
import com.pawbridge.backend.store.internal.web.StoreErrorMessages
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Scoped errors contain no order/address/account/SQL/exception body logs. */
@RestControllerAdvice(assignableTypes = [AdminOrderController::class])
class AdminOrderExceptionHandler {

    @JvmRecord
    data class FieldError(val field: String?, val value: String?, val reason: String?)

    @JvmRecord
    data class Error(val message: String?, val code: String?, val errors: List<FieldError>?)

    @ExceptionHandler(AuthenticationException::class)
    fun authentication(failure: AuthenticationException): ResponseEntity<Void> =
        ResponseEntity.status(401).build()

    @ExceptionHandler(AdministratorRejectedException::class)
    fun role(failure: AdministratorRejectedException): ResponseEntity<Error> =
        ResponseEntity.status(403).body(Error(StoreErrorMessages.ACCESS_DENIED, "C006", listOf()))

    @ExceptionHandler(AdminOrderNotFoundException::class)
    fun missing(failure: AdminOrderNotFoundException): ResponseEntity<Error> =
        ResponseEntity.status(404).body(Error(StoreErrorMessages.ORDER_NOT_FOUND, "O001", listOf()))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<Error> =
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
