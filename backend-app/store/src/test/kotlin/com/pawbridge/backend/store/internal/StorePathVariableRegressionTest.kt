package com.pawbridge.backend.store.internal

import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts
import com.pawbridge.backend.store.internal.catalogwrite.web.CatalogManagementExceptionHandler
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryManagementController
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionManagementController
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductWriteController
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler
import io.mockk.confirmVerified
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.MissingPathVariableException
import java.nio.charset.StandardCharsets

/**
 * Locks in the required (boxed) behaviour of store path variables after the Java to Kotlin conversion.
 *
 * A Kotlin `@PathVariable id: Long?` makes Spring treat the variable as optional, so a blank
 * segment (`/%20`) reached the service as null. The Java original declared a required boxed
 * `Long`, which fails with [MissingPathVariableException] before the handler runs. Every
 * path variable of the controllers below must keep that behaviour.
 *
 * Standalone MockMvc with the module exception handlers and mocked services only. It does not cover the
 * servlet container, the security filter chain or error dispatch.
 */
class StorePathVariableRegressionTest {
    private val categories: JpaCatalogCategories = mockk<JpaCatalogCategories>()
    private val options: JpaCatalogOptions = mockk<JpaCatalogOptions>()
    private val products: JpaCatalogProducts = mockk<JpaCatalogProducts>()
    private val payments: StorePaymentEvents = mockk<StorePaymentEvents>()

    private lateinit var catalogMvc: MockMvc
    private lateinit var paymentMvc: MockMvc

    @BeforeEach
    fun setUp() {
        catalogMvc = MockMvcBuilders
            .standaloneSetup(
                CategoryManagementController(categories),
                OptionManagementController(options),
                ProductWriteController(products),
            )
            .setControllerAdvice(CatalogManagementExceptionHandler())
            .build()
        paymentMvc = MockMvcBuilders
            .standaloneSetup(MockOrderPaymentController(payments))
            .setControllerAdvice(MockOrderPaymentExceptionHandler())
            .build()
    }

    // CategoryManagementController

    @Test
    fun `카테고리 ID가 공백일 때__카테고리를 수정하면__400이 되고 서비스를 호출하지 않는다`() {
        // when
        val result = performJson(catalogMvc, put("/api/v1/categories/{categoryId}", BLANK_SEGMENT))

        // then
        assertBlankRejected(result, "categoryId")
        verify(exactly = 0) { categories.updateCategory(any(), any()) }
        confirmVerified(categories)
    }

    @Test
    fun `카테고리 ID가 공백일 때__카테고리를 삭제하면__400이 되고 서비스를 호출하지 않는다`() {
        // when
        val result = catalogMvc.perform(delete("/api/v1/categories/{categoryId}", BLANK_SEGMENT)).andReturn()

        // then
        assertBlankRejected(result, "categoryId")
        verify(exactly = 0) { categories.deleteCategory(any()) }
        confirmVerified(categories)
    }

    // OptionManagementController: option groups

    @Test
    fun `옵션 그룹 ID가 공백일 때__옵션 그룹을 수정하면__400이 되고 서비스를 호출하지 않는다`() {
        // when
        val result = performJson(catalogMvc, put("/api/v1/option-groups/{groupId}", BLANK_SEGMENT))

        // then
        assertBlankRejected(result, "groupId")
        verify(exactly = 0) { options.updateOptionGroup(any(), any()) }
        confirmVerified(options)
    }

    @Test
    fun `옵션 그룹 ID가 공백일 때__옵션 그룹을 삭제하면__400이 되고 서비스를 호출하지 않는다`() {
        // when
        val result = catalogMvc.perform(delete("/api/v1/option-groups/{groupId}", BLANK_SEGMENT)).andReturn()

        // then
        assertBlankRejected(result, "groupId")
        verify(exactly = 0) { options.deleteOptionGroup(any()) }
        confirmVerified(options)
    }

    @Test
    fun `옵션 그룹 ID가 공백일 때__옵션 값을 추가하면__400이 되고 서비스를 호출하지 않는다`() {
        // when
        val result = performJson(catalogMvc, post("/api/v1/option-groups/{groupId}/values", BLANK_SEGMENT))

        // then
        assertBlankRejected(result, "groupId")
        verify(exactly = 0) { options.addOptionValue(any(), any()) }
        confirmVerified(options)
    }

    // OptionManagementController: option values

    @Test
    fun `옵션 값 ID가 공백일 때__옵션 값을 수정하면__400이 되고 서비스를 호출하지 않는다`() {
        // when
        val result = performJson(catalogMvc, put("/api/v1/option-groups/values/{valueId}", BLANK_SEGMENT))

        // then
        assertBlankRejected(result, "valueId")
        verify(exactly = 0) { options.updateOptionValue(any(), any()) }
        confirmVerified(options)
    }

    @Test
    fun `옵션 값 ID가 공백일 때__옵션 값을 삭제하면__400이 되고 서비스를 호출하지 않는다`() {
        // when
        val result = catalogMvc.perform(delete("/api/v1/option-groups/values/{valueId}", BLANK_SEGMENT))
            .andReturn()

        // then
        assertBlankRejected(result, "valueId")
        verify(exactly = 0) { options.deleteOptionValue(any()) }
        confirmVerified(options)
    }

    // ProductWriteController

    @Test
    fun `상품 ID가 공백일 때__상품을 수정하면__400이 되고 서비스를 호출하지 않는다`() {
        // when
        val result = performJson(catalogMvc, patch("/api/v1/products/{productId}", BLANK_SEGMENT))

        // then
        assertBlankRejected(result, "productId")
        verify(exactly = 0) { products.updateProduct(any(), any()) }
        confirmVerified(products)
    }

    @Test
    fun `상품 ID가 공백일 때__상품을 삭제하면__400이 되고 서비스를 호출하지 않는다`() {
        // when
        val result = catalogMvc.perform(delete("/api/v1/products/{productId}", BLANK_SEGMENT)).andReturn()

        // then
        assertBlankRejected(result, "productId")
        verify(exactly = 0) { products.deleteProduct(any()) }
        confirmVerified(products)
    }

    // MockOrderPaymentController

    @Test
    fun `주문 ID가 공백일 때__모의 결제를 처리하면__400이 되고 서비스를 호출하지 않는다`() {
        // when
        val result = paymentMvc.perform(post("/api/v1/orders/{orderId}/payment", BLANK_SEGMENT)).andReturn()

        // then
        assertBlankRejected(result, "orderId")
        verify(exactly = 0) { payments.processPayment(any()) }
        confirmVerified(payments)
    }

    @Test
    fun `주문 ID가 숫자일 때__모의 결제를 처리하면__서비스에 그 ID를 넘긴다`() {
        // given
        justRun { payments.processPayment(any()) }

        // when
        paymentMvc.perform(post("/api/v1/orders/{orderId}/payment", ID))

        // then
        verify(exactly = 1) { payments.processPayment(ID) }
        confirmVerified(payments)
    }

    companion object {
        /** Expanded into the URI template, so it reaches Spring as the blank segment `%20`. */
        private const val BLANK_SEGMENT = " "
        private const val ID = 10L
        private const val CATALOG_BODY = "{\"name\":\"x\"}"

        // helpers

        private fun performJson(mvc: MockMvc, request: MockHttpServletRequestBuilder): MvcResult {
            return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(CATALOG_BODY)).andReturn()
        }

        /**
         * Blank segment: 400, the store C005 body naming the variable, and MissingPathVariableException as the
         * resolved exception (the boxed Long path variable is kept so the blank value never reaches a service).
         */
        private fun assertBlankRejected(result: MvcResult, variable: String) {
            assertThat(result.response.status).isEqualTo(400)
            assertThat(result.response.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(
                    "{\"message\":\"요청 값의 형식이 올바르지 않습니다.\",\"code\":\"C005\"," +
                        "\"errors\":[{\"field\":\"$variable\",\"value\":\"\",\"reason\":\"typeMismatch\"}]}",
                )
            assertThat(result.resolvedException).isInstanceOf(MissingPathVariableException::class.java)
        }
    }
}
