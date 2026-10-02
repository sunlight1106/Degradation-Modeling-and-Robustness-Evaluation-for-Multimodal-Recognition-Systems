package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RateLimitFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private RateLimitFilter filter(String proxies) {
        return new RateLimitFilter(mock(StringRedisTemplate.class), new ObjectMapper().findAndRegisterModules(), true, 20, 10, 5, 2, proxies);
    }
    private MockHttpServletRequest request(String ip, String forwarded) {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(ip);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }
    @Test void forgedHeadersDoNotBypassSourceBudgetEvenWhenRedisIsUnavailable() throws Exception {
        var filter = filter("");
        for (int i = 0; i < 3; i++) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request("203.0.113.8", "192.0.2." + i), response, (req, res) -> {});
            assertThat(response.getStatus()).isEqualTo(i < 2 ? 200 : 429);
            assertThat(response.getHeader("X-RateLimit-Status")).isEqualTo("bounded-local-fallback");
        }
    }
    @Test void onlyExplicitTrustedProxyAllowsForwardedSourceAndStopsAtFirstUntrustedHop() {
        var filter = filter("10.0.0.2,10.0.0.3");
        assertThat(filter.sourceAddress(request("10.0.0.2", "198.51.100.1"))).isEqualTo("198.51.100.1");
        assertThat(filter.sourceAddress(request("10.0.0.2", "192.0.2.6, 198.51.100.1, 10.0.0.3"))).isEqualTo("198.51.100.1");
        assertThat(filter.sourceAddress(request("203.0.113.7", "198.51.100.1"))).isEqualTo("203.0.113.7");
        assertThat(filter.sourceAddress(request("10.0.0.2", "attacker.invalid"))).isEqualTo("10.0.0.2");
    }
}
