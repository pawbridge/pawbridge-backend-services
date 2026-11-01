package com.pawbridge.backend.store.internal.catalog.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime
import java.util.stream.Stream

class StoreCatalogControllerTest {
    private val catalog: StoreCatalog = mockk<StoreCatalog>()
    private val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        mvc = MockMvcBuilders.standaloneSetup(StoreCatalogController(catalog))
            .setControllerAdvice(StoreCatalogExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(json)).build()
    }

    @Test
    fun `검색 조건이 없을 때__상품 목록을 조회하면__원래 기본값을 바인딩하고 원본 결과를 돌려준다`() {
        // given
        every { catalog.search(any()) } returns StoreCatalog.SearchResult(listOf(), 0L, 0, 0, false)

        // when
        val result = mvc.perform(get("/api/v1/products"))

        // then
        result.andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"items\":[],\"totalCount\":0,\"currentPage\":0,\"totalPages\":0,\"hasNext\":false}",
                    true,
                ),
            )
        verify(exactly = 1) { catalog.search(StoreCatalog.Search(null, null, null, null, false, null, "desc", 0, 20)) }
        confirmVerified(catalog)
    }

    @Test
    fun `모든 검색 조건이 있을 때__상품 목록을 조회하면__타입이 있는 값으로 전달한다`() {
        // given
        every { catalog.search(any()) } returns StoreCatalog.SearchResult(
            listOf(
                StoreCatalog.Item(
                    7L, 17L, "사료", "색상: 파랑", "사료", null, 9007199254740993L, 0, "ACTIVE",
                    LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000), null,
                ),
            ),
            201L,
            2,
            3,
            false,
        )

        // when
        val body = mvc.perform(
            get("/api/v1/products").param("keyword", "사료").param("minPrice", "0")
                .param("maxPrice", "9007199254740993")
                .param("categoryId", "8").param("inStockOnly", "true").param("sortBy", "minPrice")
                .param("sortOrder", "asc")
                .param("page", "2").param("size", "100"),
        ).andExpect(status().isOk()).andReturn().response.contentAsByteArray

        // then
        val item = json.readTree(body).get("items").get(0)
        assertEquals(7L, item.get("id").longValue())
        assertEquals(17L, item.get("skuId").longValue())
        assertEquals("색상: 파랑", item.get("optionName").asText())
        assertEquals("사료", item.get("description").asText())
        assertEquals(9007199254740993L, item.get("price").longValue())
        assertEquals(0, item.get("totalStock").intValue())
        assertEquals("2026-10-20 01:02:03", item.get("createdAt").asText())
        assertTrue(item.get("updatedAt").isNull())
        verify(exactly = 1) {
            catalog.search(StoreCatalog.Search("사료", 0L, 9007199254740993L, 8L, true, "minPrice", "asc", 2, 100))
        }
        confirmVerified(catalog)
    }

    @Test
    fun `삭제된 상품이 모든 필드를 갖고 있을 때__상세를 조회하면__원본 필드와 null과 긴 금액과 옛 시간 형식을 유지한다`() {
        // given
        every { catalog.product(any()) } returns StoreCatalog.Product(
            9L, "상품", null, null, "DELETED", null, null, 0L,
            LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000), null,
            listOf(StoreCatalog.Sku(31L, "A", 9007199254740993L, 0, mutableMapOf("색상" to "파랑"))),
        )

        // when
        val body = mvc.perform(get("/api/v1/products/9")).andExpect(status().isOk()).andReturn().response.contentAsString

        // then
        val node = json.readTree(body)
        assertEquals(11, node.size())
        assertFalse(node.has("data"))
        assertEquals("DELETED", node.get("status").asText())
        assertTrue(node.get("categoryId").isNull())
        assertTrue(node.get("description").isNull())
        assertTrue(node.get("imageUrl").isNull())
        assertTrue(node.get("updatedAt").isNull())
        assertEquals("2026-10-20 01:02:03", node.get("createdAt").asText())
        assertEquals(9007199254740993L, node.get("skus").get(0).get("price").longValue())
        assertEquals(0, node.get("skus").get(0).get("stockQuantity").intValue())
    }

    @Test
    fun `카테고리와 옵션 메타데이터가 있을 때__조회하면__트리와 그룹의 전송 모양을 보존한다`() {
        // given
        every { catalog.categories() } returns listOf(
            StoreCatalog.Category(
                1L, "루트", null, null,
                listOf(StoreCatalog.Category(2L, "자식", "설명", 1L, listOf())),
            ),
        )
        every { catalog.optionGroups() } returns listOf(StoreCatalog.OptionGroup(3L, "색상", mutableListOf()))
        every { catalog.optionGroup(any()) } returns
            StoreCatalog.OptionGroup(3L, "색상", mutableListOf(StoreCatalog.OptionValue(4L, "파랑", 3L, "색상")))

        // when
        val categories = mvc.perform(get("/api/v1/categories"))
        val groups = mvc.perform(get("/api/v1/option-groups"))
        val group = mvc.perform(get("/api/v1/option-groups/3"))

        // then
        categories.andExpect(status().isOk())
            .andExpect(jsonPath("\$[0].children[0].parentId").value(1))
        groups.andExpect(status().isOk())
            .andExpect(jsonPath("\$[0].values").isEmpty())
        group.andExpect(status().isOk())
            .andExpect(jsonPath("\$.values[0].groupName").value("색상"))
    }

    @ParameterizedTest
    @MethodSource("errors")
    fun `카탈로그 실패가 있을 때__조회하면__원래 상태와 오류를 비공개 상세 없이 유지한다`(
        path: String,
        failure: RuntimeException,
        statusCode: Int,
        code: String,
        message: String,
    ) {
        // given
        if (path == "/api/v1/products") {
            every { catalog.search(any()) } throws failure
        } else if (path.contains("option-groups")) {
            every { catalog.optionGroup(any()) } throws failure
        } else {
            every { catalog.product(any()) } throws failure
        }

        // when
        val result = mvc.perform(get(path))

        // then
        result.andExpect(status().`is`(statusCode))
            .andExpect(
                content().json(
                    json.writeValueAsString(StoreCatalogExceptionHandler.Error(message, code, listOf())),
                    true,
                ),
            )
    }

    @ParameterizedTest
    @MethodSource("wrongTypes")
    fun `경로나 쿼리 타입이 틀렸을 때__조회하면__애플리케이션 호출 전에 원래의 필드 오류를 돌려준다`(
        path: String,
        field: String,
        value: String,
    ) {
        // given

        // when
        val result = mvc.perform(get(path))

        // then
        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value(field))
            .andExpect(jsonPath("\$.errors[0].value").value(value))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))
        confirmVerified(catalog)
    }

    @Test
    fun `읽기 전용 컨트롤러일 때__상품을 POST하면__쓰기 핸들러가 없다`() {
        // given

        // when
        val result = mvc.perform(post("/api/v1/products"))

        // then
        result.andExpect(status().isMethodNotAllowed())
        confirmVerified(catalog)
    }

    companion object {
        @JvmStatic
        fun errors(): Stream<Arguments> = Stream.of(
            Arguments.of("/api/v1/products", IllegalArgumentException("private validation"), 400, "C001", "입력값이 올바르지 않습니다."),
            Arguments.of("/api/v1/products/9", IllegalArgumentException("Product not found: 9"), 400, "C001", "입력값이 올바르지 않습니다."),
            Arguments.of(
                "/api/v1/option-groups/9",
                IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: 9"),
                400,
                "C001",
                "입력값이 올바르지 않습니다.",
            ),
            Arguments.of(
                "/api/v1/products",
                CatalogSearchUnavailableException(RuntimeException("private SQL")),
                503,
                "P006",
                "검색 서비스를 준비 중입니다. 잠시 후 다시 시도해 주세요.",
            ),
            Arguments.of("/api/v1/products/9", DataAccessResourceFailureException("private SQL"), 500, "C004", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
        )

        @JvmStatic
        fun wrongTypes(): Stream<Arguments> = Stream.of(
            Arguments.of("/api/v1/products/abc", "productId", "abc"),
            Arguments.of("/api/v1/products?size=x", "size", "x"),
            Arguments.of("/api/v1/option-groups/abc", "groupId", "abc"),
        )
    }
}
