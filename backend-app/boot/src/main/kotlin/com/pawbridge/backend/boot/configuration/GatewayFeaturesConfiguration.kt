package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.security.LostSearchLimitsFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Servlet filters for the features the API Gateway used to provide. CORS lives in [ReadSecurityConfiguration]
 * (the preflight must pass Spring Security) and health checks in the actuator properties.
 */
@Configuration(proxyBeanMethods = false)
class GatewayFeaturesConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.animal-lost-search", name = ["enabled"], havingValue = "true")
    fun lostSearchLimitsFilter(
        @Value("\${lost-search.max-request-bytes:6291456}") maxBytes: Int,
        // 실종 검색 동시 입장 수. Python(lost_search/router.py)이 검색을 한 번에 하나씩 돌리고 나머지를
        // 도착 순서대로 MAX_WAITING(10)개까지 기다리게 하므로 그 줄 크기(검색 중 1 + 대기 10)만큼 들여보낸다.
        // 넘치면 Python을 부르기 전에 503이다. Python 값과 함께 바꾼다.
        @Value("\${lost-search.max-concurrent:11}") maxConcurrent: Int,
        @Value("\${lost-search.requests-per-minute:30}") requestsPerMinute: Int,
        @Value("\${lost-search.timeout-seconds:50}") timeoutSeconds: Int,
    ): FilterRegistrationBean<LostSearchLimitsFilter> {
        val registration = FilterRegistrationBean(
            LostSearchLimitsFilter(maxBytes, maxConcurrent, requestsPerMinute, timeoutSeconds),
        )
        registration.order = LOST_SEARCH_ORDER
        registration.addUrlPatterns(LostSearchLimitsFilter.PATH, LostSearchLimitsFilter.PATH + "/")
        return registration
    }

    companion object {
        /** After Security, so rejections already carry the CORS headers and the public route rule has been applied. */
        const val LOST_SEARCH_ORDER: Int = -50
    }
}
