package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse

@JvmRecord
data class LostSearchResponse(
    val candidates: List<Candidate>,
    // 보호 중 사망한 기록. 요청에서 includeDeceased를 켰을 때만 채우고, 그 밖에는 빈 목록이다.
    val deceasedCandidates: List<Candidate> = emptyList(),
) {
    // Image scores are internal ranking signals, not identity probabilities.
    @JvmRecord
    data class Candidate(
        val animal: AnimalDetailResponse,
        val shelterPhone: String?,
        val matchedEvidence: List<String?>?,
    )
}
