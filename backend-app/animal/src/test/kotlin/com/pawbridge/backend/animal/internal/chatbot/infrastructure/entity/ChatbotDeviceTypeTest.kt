package com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class ChatbotDeviceTypeTest {

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148|MOBILE",
            "Mozilla/5.0 (Linux; Android 15; SM-S928N) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36|MOBILE",
            "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 Chrome/131.0 Safari/537.36|MOBILE",
            "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148|MOBILE",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36|DESKTOP",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15|DESKTOP",
            "curl/8.7.1|DESKTOP",
        ],
    )
    fun `User-Agent가 있을 때__기기 종류를 나누면__휴대폰과 태블릿은 MOBILE, 나머지는 DESKTOP이다`(
        userAgent: String,
        expected: ChatbotDeviceType,
    ) {
        assertEquals(expected, ChatbotDeviceType.fromUserAgent(userAgent))
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "   "])
    fun `User-Agent가 없거나 비어 있을 때__기기 종류를 나누면__알 수 없으므로 null이다`(userAgent: String?) {
        assertNull(ChatbotDeviceType.fromUserAgent(userAgent))
    }
}
