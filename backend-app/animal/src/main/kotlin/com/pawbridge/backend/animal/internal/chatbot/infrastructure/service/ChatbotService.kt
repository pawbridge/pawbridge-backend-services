package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.PythonChatbotClient
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.AnimalChatbotContext
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotGuardResult
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageResponse
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotRecentMessage
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotBlockLog
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotMessage
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotSession
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotAnimalNotFoundException
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotBlockLogRepository
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotMessageRepository
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository.ChatbotSessionRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.transaction.support.TransactionOperations

class ChatbotService(
    private val animalRepository: AnimalRepository,
    private val chatbotSessionRepository: ChatbotSessionRepository,
    private val chatbotMessageRepository: ChatbotMessageRepository,
    private val chatbotBlockLogRepository: ChatbotBlockLogRepository,
    private val chatbotCookieService: ChatbotCookieService,
    private val chatbotClientIpResolver: ChatbotClientIpResolver,
    private val chatbotIpHashService: ChatbotIpHashService,
    private val chatbotRateLimitService: ChatbotRateLimitService,
    private val chatbotGuardService: ChatbotGuardService,
    private val pythonAiServiceClient: PythonChatbotClient,
    private val pythonAiInternalApiKey: String?,
    private val reads: TransactionOperations,
    private val writes: TransactionOperations,
) {

    fun createMessage(
        animalId: Long?,
        request: ChatbotMessageRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ChatbotMessageResponse {
        val anonymousSessionId = chatbotCookieService.resolveAnonymousSessionId(httpRequest, httpResponse)
        val clientIp = chatbotClientIpResolver.resolve(httpRequest)
        val ipHash = chatbotIpHashService.hash(clientIp)

        val animal = reads.execute {
            animalRepository.findWithShelterById(animalId)
                .orElseThrow { ChatbotAnimalNotFoundException() }
        }!!
        val session = resolveSession(request.sessionId, anonymousSessionId, animal)
        chatbotRateLimitService.check(anonymousSessionId, ipHash)

        if (reads.execute { chatbotMessageRepository.countBySession(session) }!! >= MAX_SESSION_MESSAGES) {
            val guardResult = ChatbotGuardResult.blocked(
                "RATE_LIMIT",
                "SESSION_MESSAGE_LIMIT",
                "이 대화에서 사용할 수 있는 메시지 수를 초과했습니다. 새로고침 후 새 대화를 시작해주세요.",
                false,
            )
            saveBlockLog(animal, anonymousSessionId, ipHash, request.question, guardResult)
            return blockedResponse(session, guardResult)
        }

        val guardResult = chatbotGuardService.inspect(request.question)
        if (guardResult.blocked) {
            saveBlockLog(animal, anonymousSessionId, ipHash, request.question, guardResult)
            return blockedResponse(session, guardResult)
        }

        val recentMessages = getRecentMessages(session)

        val aiResponse = pythonAiServiceClient.createChatbotMessage(
            pythonAiInternalApiKey,
            PythonChatbotMessageRequest(
                toAnimalContext(animal),
                recentMessages,
                guardResult.questionForLlm,
            ),
        )

        // Java와 같이 응답 필드는 쓰기 트랜잭션 안에서 읽는다(응답이 null이면 트랜잭션 안에서 NPE).
        writes.executeWithoutResult {
            chatbotMessageRepository.saveAll(
                java.util.List.of(
                    ChatbotMessage.user(session, guardResult.questionForLlm),
                    ChatbotMessage.assistant(
                        session,
                        withoutCitations(aiResponse!!.answer),
                        aiResponse.provider,
                        aiResponse.safetyNotice,
                    ),
                ),
            )
        }

        return ChatbotMessageResponse(
            session.id,
            aiResponse!!.answer,
            aiResponse.safetyNotice,
            aiResponse.provider,
            aiResponse.sources.orEmpty(),
            aiResponse.healthCards.orEmpty(),
        )
    }

    private fun blockedResponse(session: ChatbotSession, guardResult: ChatbotGuardResult): ChatbotMessageResponse {
        return ChatbotMessageResponse(
            session.id,
            guardResult.userMessage,
            SAFETY_NOTICE,
            "guard",
        )
    }

    private fun saveBlockLog(
        animal: Animal,
        anonymousSessionId: String,
        ipHash: String,
        question: String?,
        guardResult: ChatbotGuardResult,
    ) {
        val preview = if (guardResult.sensitive) null else toPreview(question)
        writes.executeWithoutResult {
            chatbotBlockLogRepository.save(
                ChatbotBlockLog(
                    animal,
                    anonymousSessionId,
                    ipHash,
                    guardResult.category,
                    guardResult.reason,
                    question?.length ?: 0,
                    preview,
                ),
            )
        }
    }

    private fun toPreview(question: String?): String? {
        if (question == null || isBlank(question)) {
            return null
        }
        val normalized = javaTrim(question)
        if (normalized.length <= QUESTION_PREVIEW_MAX_LENGTH) {
            return normalized
        }
        return normalized.substring(0, QUESTION_PREVIEW_MAX_LENGTH)
    }

    private fun resolveSession(sessionId: String?, anonymousSessionId: String, animal: Animal): ChatbotSession {
        if (sessionId != null && !isBlank(sessionId)) {
            return reads.execute {
                chatbotSessionRepository.findByIdAndAnonymousSessionIdAndAnimal_Id(
                    sessionId,
                    anonymousSessionId,
                    animal.id,
                ).orElseThrow { IllegalArgumentException("Invalid chatbot session") }
            }!!
        }
        return writes.execute { chatbotSessionRepository.save(ChatbotSession(anonymousSessionId, animal)) }!!
    }

    private fun getRecentMessages(session: ChatbotSession): List<ChatbotRecentMessage> {
        val messages = ArrayList(
            reads.execute { chatbotMessageRepository.findTop6BySessionOrderByCreatedAtDesc(session) }!!,
        )
        messages.reverse()
        return messages.map { message ->
            ChatbotRecentMessage(
                message.role!!.apiValue,
                message.content,
            )
        }
    }

    private fun toAnimalContext(animal: Animal): AnimalChatbotContext {
        return AnimalChatbotContext(
            animal.species?.name,
            animal.breed,
            if (animal.birthYear != null) animal.birthYear.toString() + "년생" else null,
            animal.weight,
            animal.color,
            animal.gender?.name,
            animal.neuterStatus?.name,
            animal.specialMark,
            animal.apmsProcessState,
        )
    }

    // 건강 해설 근거 번호([1])는 그 응답에서만 뜻이 있다. 저장된 답은 다음 질문의 최근 대화로 다시 쓰이고
    // 그때는 근거 번호가 새로 매겨지므로, 저장할 때만 지운다(화면으로 가는 답에는 남긴다).
    // 가드가 넣은 [PHONE], [EMAIL] 같은 표시는 숫자가 아니라 지우지 않는다.
    private fun withoutCitations(answer: String?): String? = answer?.replace(CITATION, "")

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    // Java String.trim() removes every character up to U+0020; Kotlin trim() uses Character.isWhitespace.
    private fun javaTrim(value: String): String = value.trim { it <= ' ' }

    private companion object {
        private val CITATION = Regex("""\s?\[\d+]""")
        private const val MAX_SESSION_MESSAGES = 20
        private const val QUESTION_PREVIEW_MAX_LENGTH = 200
        private const val SAFETY_NOTICE =
            "이 답변은 일반적인 참고 정보이며, 정확한 건강 상태나 치료 판단은 동물병원 또는 수의사에게 확인해 주세요."
    }
}
