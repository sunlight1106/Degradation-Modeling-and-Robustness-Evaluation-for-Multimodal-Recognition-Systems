package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int generalLimit;
    private final int uploadLimit;
    private final int inferenceLimit;

    public RateLimitFilter(StringRedisTemplate redis, ObjectMapper objectMapper,
                           @Value("${app.rate-limit.enabled:true}") boolean enabled,
                           @Value("${app.rate-limit.general-per-minute:120}") int generalLimit,
                           @Value("${app.rate-limit.upload-per-minute:30}") int uploadLimit,
                           @Value("${app.rate-limit.inference-per-minute:20}") int inferenceLimit) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.generalLimit = generalLimit;
        this.uploadLimit = uploadLimit;
        this.inferenceLimit = inferenceLimit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !enabled || path.startsWith("/actuator/") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        int limit = path.equals("/api/v1/files") && "POST".equals(request.getMethod()) ? uploadLimit
                : path.equals("/api/v1/inference/tasks") && "POST".equals(request.getMethod()) ? inferenceLimit : generalLimit;
        String actor = actor(request);
        long minute = Instant.now().getEpochSecond() / 60;
        String key = "rate:" + actor + ":" + category(path) + ":" + minute;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, Duration.ofSeconds(75));
            response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - (count == null ? 0 : count))));
            if (count != null && count > limit) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure("RATE_LIMIT_EXCEEDED", "请求过于频繁，请稍后再试"));
                return;
            }
        } catch (Exception ignored) {
            response.setHeader("X-RateLimit-Status", "degraded");
        }
        chain.doFilter(request, response);
    }

    private String actor(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
            return "user:" + authentication.getName();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
        return "ip:" + ip.replaceAll("[^0-9A-Fa-f:.]", "_");
    }

    private String category(String path) {
        if (path.contains("/inference/")) return "inference";
        if (path.contains("/files")) return "files";
        if (path.contains("/auth/")) return "auth";
        return "general";
    }
}
