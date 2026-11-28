package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service

import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ChatbotIpHashService(
    private val ipHashSecret: String?,
) {

    fun hash(clientIp: String?): String {
        if (ipHashSecret == null || isBlank(ipHashSecret)) {
            throw IllegalStateException("chatbot.security.ip-hash-secret 설정이 필요합니다.")
        }

        try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(ipHashSecret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
            val digest = mac.doFinal(clientIp!!.toByteArray(StandardCharsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("IP hash 생성에 실패했습니다.", e)
        } catch (e: InvalidKeyException) {
            throw IllegalStateException("IP hash 생성에 실패했습니다.", e)
        }
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    private companion object {
        private const val HMAC_ALGORITHM = "HmacSHA512"
    }
}
