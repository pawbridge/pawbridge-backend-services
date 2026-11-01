package com.pawbridge.backend.store.internal.web

/** Korean messages for the Store error envelope; the codes (C001 etc.) stay the machine-readable part. */
object StoreErrorMessages {

    const val INVALID_INPUT: String = "입력값이 올바르지 않습니다."

    const val INVALID_TYPE: String = "요청 값의 형식이 올바르지 않습니다."

    const val METHOD_NOT_ALLOWED: String = "지원하지 않는 요청 방식입니다."

    const val ACCESS_DENIED: String = "접근 권한이 없습니다."

    const val ORDER_NOT_FOUND: String = "주문을 찾을 수 없습니다."

    const val SERVER_ERROR: String = "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
}
