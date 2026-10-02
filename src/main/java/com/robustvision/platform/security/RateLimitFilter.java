package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Redis-backed fixed windows; a bounded fail-closed fallback protects outages too. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],75) end; return n", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int generalLimit, uploadLimit, inferenceLimit, authLimit;
    private final Set<String> trustedProxies;
    private final Map<String, Long> fallback = new HashMap<>();
    private long fallbackMinute = -1;

    public RateLimitFilter(StringRedisTemplate redis, ObjectMapper objectMapper,
                           @Value("${app.rate-limit.enabled:true}") boolean enabled,
                           @Value("${app.rate-limit.general-per-minute:120}") int generalLimit,
                           @Value("${app.rate-limit.upload-per-minute:30}") int uploadLimit,
                           @Value("${app.rate-limit.inference-per-minute:20}") int inferenceLimit,
                           @Value("${app.rate-limit.auth-per-minute:10}") int authLimit,
                           @Value("${app.rate-limit.trusted-proxies:}") String trustedProxies) {
        this.redis = redis; this.objectMapper = objectMapper; this.enabled = enabled;
        this.generalLimit = generalLimit; this.uploadLimit = uploadLimit;
        this.inferenceLimit = inferenceLimit;
        this.authLimit = authLimit;
        this.trustedProxies = Arrays.stream(trustedProxies.split(",")).map(String::trim)
                .filter(s -> !s.isBlank()).map(RateLimitFilter::numericAddress)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !enabled || path.startsWith("/actuator/") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String category = category(request);
        int limit = switch (category) { case "auth" -> authLimit; case "inference" -> inferenceLimit;
            case "files" -> uploadLimit; default -> generalLimit; };
        long minute = Instant.now().getEpochSecond() / 60;
        // Authentication and security operations always have a source-IP budget, including logged-in requests.
        String actor = "auth".equals(category) ? "ip:" + sourceAddress(request) : actor(request);
        String key = "rate:" + actor + ":" + category + ":" + minute;
        long count;
        try {
            Long value = redis.execute(INCREMENT, List.of(key));
            if (value == null) throw new IllegalStateException("rate store unavailable");
            count = value;
        } catch (Exception ignored) {
            response.setHeader("X-RateLimit-Status", "bounded-local-fallback");
            count = fallbackCount(key, minute);
        }
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - count)));
        if (count > limit) {
            response.setStatus(429); response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE); response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure("RATE_LIMIT_EXCEEDED", "请求过于频繁，请稍后再试"));
            return;
        }
        chain.doFilter(request, response);
    }

    private synchronized long fallbackCount(String key, long minute) {
        if (fallbackMinute != minute) { fallback.clear(); fallbackMinute = minute; }
        if (!fallback.containsKey(key) && fallback.size() >= 10000) return Long.MAX_VALUE;
        long value = fallback.getOrDefault(key, 0L) + 1;
        fallback.put(key, value); return value;
    }

    private String actor(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
            return "user:" + authentication.getName();
        }
        return "ip:" + sourceAddress(request);
    }

    String sourceAddress(HttpServletRequest request) {
        String remote = numericAddress(request.getRemoteAddr());
        if (!trustedProxies.contains(remote)) return remote;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 512) return remote;
        String[] hops = forwarded.split(",", -1);
        if (hops.length > 10) return remote;
        String current = remote;
        for (int i = hops.length - 1; i >= 0 && trustedProxies.contains(current); i--) {
            try { current = numericAddress(hops[i].trim()); }
            catch (IllegalArgumentException invalid) { return remote; }
        }
        return current;
    }

    private static String numericAddress(String value) {
        if (value == null || !(value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || value.contains(":") && value.matches("[0-9A-Fa-f:.]+"))) {
            throw new IllegalArgumentException("Trusted proxies must be literal IP addresses");
        }
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid literal IP address"); }
    }

    private String category(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        if (mutation && (path.contains("/auth/") || path.startsWith("/api/v1/account/"))) return "auth";
        if ("POST".equals(request.getMethod()) && (path.equals("/api/v1/inference/tasks")
                || path.matches("/api/v1/inference/tasks/[^/]+/recover")
                || path.equals("/api/v1/personal-ai/execute") || path.equals("/api/v1/personal-ai/recognition/execute"))) return "inference";
        if ("POST".equals(request.getMethod()) && path.equals("/api/v1/files")) return "files";
        return "general";
    }
}
