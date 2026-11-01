package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages
import org.assertj.core.api.Assertions.assertThat
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.multipart.MultipartFile

/** HTTP body/status/dispatch only; Root must verify the real JWT/current-admin security boundary. */
class CatalogManagementControllerTest {
    private val products = mockk<JpaCatalogProducts>()
    private val options = mockk<JpaCatalogOptions>()
    private val images = mockk<S3ProductImages>()
    private val categories = mockk<JpaCatalogCategories>()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        mvc = MockMvcBuilders.standaloneSetup(
            ProductWriteController(products),
            OptionManagementController(options),
            ProductImageController(images),
            CategoryManagementController(categories),
        ).setControllerAdvice(CatalogManagementExceptionHandler()).build()
    }

    @Test
    fun `상품 본문이 있을 때__생성하면__원래 필드를 바인딩하고 201을 돌려준다`() {
        // given
        val created = ProductResponse.builder().id(10L).name("food").status("ACTIVE")
            .description("description").imageUrl("image.png").categoryId(1L).categoryName("food category")
            .skus(
                listOf(
                    SkuDetailDto.builder().skuId(101L).skuCode("FOOD-S").price(100L).stockQuantity(3)
                        .options(mapOf("size" to "small")).build(),
                ),
            ).build()

        every { products.createProduct(any()) } returns created

        // when
        val result = mvc.perform(
            post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(
                """
                {"name":"food","description":"description","imageUrl":"image.png","categoryId":1,
                 "skus":[{"skuCode":"FOOD-S","price":100,"stockQuantity":3,"optionValueIds":[2]}]}
                """.trimIndent() + "\n",
            ),
        )

        // then
        result.andExpect(status().isCreated())
            .andExpect(jsonPath("\$.id").value(10))
            .andExpect(jsonPath("\$.categoryName").value("food category"))
            .andExpect(jsonPath("\$.skus[0].skuId").value(101))
            .andExpect(jsonPath("\$.skus[0].options.size").value("small"))
        val request = slot<ProductCreateRequest>()
        verify(exactly = 1) { products.createProduct(capture(request)) }
        assertThat(request.captured.categoryId).isEqualTo(1L)
        assertThat(request.captured.skus!!.first().optionValueIds).containsExactly(2L)
        confirmVerified(products)
    }

    @Test
    fun `상품 수정과 삭제 요청이 있을 때__전달하면__원래 경로와 상태 코드를 유지한다`() {
        // given
        val updated = ProductResponse.builder().id(10L).name("new food").status("HIDDEN").skus(listOf()).build()

        every { products.updateProduct(any(), any()) } returns updated
        justRun { products.deleteProduct(any()) }

        // when
        val patched = mvc.perform(
            patch("/api/v1/products/10").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new food\",\"status\":\"HIDDEN\"}"),
        )
        val deleted = mvc.perform(delete("/api/v1/products/10"))

        // then
        patched.andExpect(status().isOk()).andExpect(jsonPath("\$.status").value("HIDDEN"))
        deleted.andExpect(status().isNoContent()).andExpect(content().string(""))
        val request = slot<ProductUpdateRequest>()
        verify(exactly = 1) { products.updateProduct(10L, capture(request)) }
        assertThat(request.captured.name).isEqualTo("new food")
        verify(exactly = 1) { products.deleteProduct(10L) }
        confirmVerified(products)
    }

    @Test
    fun `옵션 그룹 쓰기 요청이 있을 때__전달하면__원래 메서드와 응답 모양을 유지한다`() {
        // given
        val response = OptionGroupResponse.builder().id(1L).name("size").values(listOf()).build()

        every { options.createOptionGroup(any()) } returns response
        every { options.updateOptionGroup(any(), any()) } returns response
        justRun { options.deleteOptionGroup(any()) }

        // when
        val created = mvc.perform(
            post("/api/v1/option-groups").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"size\",\"values\":[\"small\"]}"),
        )
        val updated = mvc.perform(
            put("/api/v1/option-groups/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"size\"}"),
        )
        val deleted = mvc.perform(delete("/api/v1/option-groups/1"))

        // then
        created.andExpect(status().isCreated())
            .andExpect(jsonPath("\$.id").value(1))
            .andExpect(jsonPath("\$.values").isArray())
        updated.andExpect(status().isOk())
        deleted.andExpect(status().isNoContent())
        val request = slot<OptionGroupRequest>()
        verify(exactly = 1) { options.createOptionGroup(capture(request)) }
        assertThat(request.captured.values).containsExactly("small")
        verify(exactly = 1) { options.updateOptionGroup(1L, any()) }
        verify(exactly = 1) { options.deleteOptionGroup(1L) }
        confirmVerified(options)
    }

    @Test
    fun `옵션 값 쓰기 요청이 있을 때__전달하면__원래 메서드와 그룹 신원을 유지한다`() {
        // given
        val response = OptionValueResponse.builder().id(2L).name("small").groupId(1L).groupName("size").build()

        every { options.addOptionValue(any(), any()) } returns response
        every { options.updateOptionValue(any(), any()) } returns response
        justRun { options.deleteOptionValue(any()) }

        // when
        val created = mvc.perform(
            post("/api/v1/option-groups/1/values").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"small\"}"),
        )
        val updated = mvc.perform(
            put("/api/v1/option-groups/values/2").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"small\"}"),
        )
        val deleted = mvc.perform(delete("/api/v1/option-groups/values/2"))

        // then
        created.andExpect(status().isCreated())
            .andExpect(jsonPath("\$.groupId").value(1))
            .andExpect(jsonPath("\$.groupName").value("size"))
        updated.andExpect(status().isOk())
        deleted.andExpect(status().isNoContent())
        val request = slot<OptionValueRequest>()
        verify(exactly = 1) { options.addOptionValue(1L, capture(request)) }
        assertThat(request.captured.name).isEqualTo("small")
        verify(exactly = 1) { options.updateOptionValue(2L, any()) }
        verify(exactly = 1) { options.deleteOptionValue(2L) }
        confirmVerified(options)
    }

    @Test
    fun `멀티파트 이미지가 있을 때__업로드하면__원래의 imageUrl 봉투를 돌려준다`() {
        // given
        val image = MockMultipartFile("file", "photo.png", "image/png", byteArrayOf(1))

        every { images.uploadImage(any()) } returns "https://assets.invalid/products/photo.png"

        // when
        val result = mvc.perform(multipart("/api/v1/images").file(image))

        // then
        result.andExpect(status().isOk())
            .andExpect(jsonPath("\$.imageUrl").value("https://assets.invalid/products/photo.png"))
        verify(exactly = 1) { images.uploadImage(any<MultipartFile>()) }
        confirmVerified(images)
    }

    @Test
    fun `카테고리 쓰기 요청이 있을 때__전달하면__원래 메서드와 상태 코드와 트리 모양을 유지한다`() {
        // given
        val child = CategoryResponse.builder().id(2L).name("dry").parentId(1L).children(listOf()).build()
        val response = CategoryResponse.builder().id(1L).name("food").description("food category")
            .children(listOf(child)).build()

        every { categories.createCategory(any()) } returns response
        every { categories.updateCategory(any(), any()) } returns response
        justRun { categories.deleteCategory(any()) }

        // when
        val created = mvc.perform(
            post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"food\",\"description\":\"food category\",\"parentId\":5}"),
        )
        val updated = mvc.perform(
            put("/api/v1/categories/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"food\",\"parentId\":null}"),
        )
        val deleted = mvc.perform(delete("/api/v1/categories/1"))

        // then
        created.andExpect(status().isCreated())
            .andExpect(jsonPath("\$.id").value(1))
            .andExpect(jsonPath("\$.parentId").isEmpty())
            .andExpect(jsonPath("\$.children[0].parentId").value(1))
            .andExpect(jsonPath("\$.children[0].children").isEmpty())
        updated.andExpect(status().isOk()).andExpect(jsonPath("\$.name").value("food"))
        deleted.andExpect(status().isNoContent()).andExpect(content().string(""))
        val createdRequest = slot<CategoryCreateRequest>()
        verify(exactly = 1) { categories.createCategory(capture(createdRequest)) }
        assertThat(createdRequest.captured.name).isEqualTo("food")
        assertThat(createdRequest.captured.description).isEqualTo("food category")
        assertThat(createdRequest.captured.parentId).isEqualTo(5L)
        val updatedRequest = slot<CategoryUpdateRequest>()
        verify(exactly = 1) { categories.updateCategory(1L, capture(updatedRequest)) }
        assertThat(updatedRequest.captured.parentId).isNull()
        verify(exactly = 1) { categories.deleteCategory(1L) }
        confirmVerified(categories)
    }

    @Test
    fun `카테고리 업무 실패가 있을 때__매핑하면__원래의 400 입력 오류와 500 상태 오류 코드를 유지한다`() {
        // given
        every { categories.updateCategory(any(), any()) } throws IllegalArgumentException("synthetic self parent")
        every { categories.deleteCategory(any()) } throws IllegalStateException("synthetic child refusal")

        // when
        val updated = mvc.perform(
            put("/api/v1/categories/1").contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":1}"),
        )
        val deleted = mvc.perform(delete("/api/v1/categories/1"))
        val mistyped = mvc.perform(delete("/api/v1/categories/abc"))

        // then
        updated.andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C001"))
            .andExpect(jsonPath("\$.errors").isEmpty())
        deleted.andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
        mistyped.andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.message").value("요청 값의 형식이 올바르지 않습니다."))
    }

    @Test
    fun `상품 업무 실패가 있을 때__매핑하면__원래의 400 입력 오류와 500 상태 오류 코드를 유지한다`() {
        // given
        every { products.createProduct(any()) } throws IllegalArgumentException("synthetic invalid input")
        every { products.deleteProduct(any()) } throws IllegalStateException("synthetic cart refusal")

        // when
        val created = mvc.perform(
            post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"food\"}"),
        )
        val deleted = mvc.perform(delete("/api/v1/products/10"))

        // then
        created.andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C001"))
            .andExpect(jsonPath("\$.message").value("입력값이 올바르지 않습니다."))
            .andExpect(jsonPath("\$.errors").isEmpty())
        deleted.andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
            .andExpect(jsonPath("\$.message").value("서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."))
    }
}
