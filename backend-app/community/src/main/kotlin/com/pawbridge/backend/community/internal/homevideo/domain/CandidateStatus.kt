package com.pawbridge.backend.community.internal.homevideo.domain

enum class CandidateStatus {
    /** 판정에서 맞음. 교체나 빈칸 채우기를 기다리는 예비 후보. */
    WAITING,

    /** 판정에서 아님. 30일 동안 다시 판정하지 않는다. */
    REJECTED,

    /** 홈에 올린 적이 있다. 다시 넣지 않는다. */
    USED,
}
