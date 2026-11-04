package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

@JvmRecord
data class PythonLostSearchResponse(
    val candidates: List<Candidate?>?,
    // 네이티브는 includeDeceased를 보낸 요청에만 이 필드를 넣는다.
    val deceasedCandidates: List<Candidate?>? = null,
) {
    @JvmRecord
    data class Candidate(
        val animalId: Long?,
        val imageScore: Double?,
        val matchedEvidence: List<String?>?,
    )
}
