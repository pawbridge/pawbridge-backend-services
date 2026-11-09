package com.pawbridge.backend.animal.internal.travel.application

/**
 * [reason], [operation], [status]는 수집 실패 로그에서 원인을 나누는 값이다.
 * 메시지와 원인 예외에는 담지 않는다. 원인 예외의 메시지에는 서비스 키가 든 요청 URL이 들어갈 수 있다.
 */
class PetTravelException(
    val code: Code,
    val reason: String = code.name,
    val operation: String? = null,
    val status: Int? = null,
) : RuntimeException(code.name) {

    enum class Code { INVALID_REQUEST, NOT_FOUND, UNAVAILABLE }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun unavailable(reason: String = Code.UNAVAILABLE.name, operation: String? = null, status: Int? = null): PetTravelException {
            return PetTravelException(Code.UNAVAILABLE, reason, operation, status)
        }
    }
}
