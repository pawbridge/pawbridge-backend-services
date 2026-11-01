package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.store-mock-payment.enabled=TrUe"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        MockOrderPaymentController::class,
        MockOrderPaymentExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreMockPaymentHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var payments: StorePaymentEvents

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `검증된 어떤 역할이든__임의 주문 ID를 결제하면__소유자 확인 없이 기존 비즈니스를 실행한다`(
        role: String,
    ) {
        // given
        val request = command("POST", "/api/v1/orders/41/payment")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, role))

        justRun { payments.processPayment(any()) }

        // when
        val response = mvc.perform(request)

        // then
        val result = response
            .andExpect(status().isOk())
            .andReturn()
        verify(exactly = 1) { payments.processPayment(41L) }
        confirmVerified(payments)
        assertThat(result.response.contentAsByteArray).isEmpty()
    }

    @Test
    fun `주문이 없을 때__결제하면__기존 잘못된 인자 예외를 C001로 변환한다`() {
        // given
        every { payments.processPayment(any()) } throws IllegalArgumentException("Order not found: 41")

        // when
        val result = mvc.perform(verified("POST", "/api/v1/orders/41/payment"))

        // then
        val json = mapper.readTree(
            result
                .andExpect(status().isBadRequest())
                .andReturn().response.contentAsByteArray,
        )
        verify(exactly = 1) { payments.processPayment(41L) }
        assertThat(json.path("message").asText()).isEqualTo("입력값이 올바르지 않습니다.")
        assertThat(json.path("code").asText()).isEqualTo("C001")
        assertThat(json.path("errors").isArray).isTrue()
        assertThat(json.path("errors")).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["state", "runtime"])
    fun `대기 상태가 아닌 주문이거나 이벤트가 실패했을 때__결제하면__기존 서버 오류로 변환한다`(
        failure: String,
    ) {
        // given
        val thrown: RuntimeException = if (failure == "state") {
            IllegalStateException("Order is not in PENDING state. Current: PAID")
        } else {
            RuntimeException("Failed to publish payment event")
        }

        every { payments.processPayment(any()) } throws thrown

        // when
        val result = mvc.perform(verified("POST", "/api/v1/orders/41/payment"))

        // then
        val json = mapper.readTree(
            result
                .andExpect(status().isInternalServerError())
                .andReturn().response.contentAsByteArray,
        )
        verify(exactly = 1) { payments.processPayment(41L) }
        assertThat(json.path("message").asText()).isEqualTo("서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")
        assertThat(json.path("code").asText()).isEqualTo("C004")
        assertThat(json.toString()).doesNotContain("PENDING").doesNotContain("publish")
    }

    companion object {
        fun command(method: String, path: String): MockHttpServletRequestBuilder {
            return request(HttpMethod.valueOf(method), path).header("X-User-Id", "99")
        }

        fun verified(method: String, path: String): MockHttpServletRequestBuilder {
            return command(method, path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }
    }
}
