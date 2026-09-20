package com.robustvision.platform.common;

import org.slf4j.MDC;

import java.time.Instant;

public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        String traceId,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, currentTraceId(), Instant.now());
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(false, null, new ApiError(code, message), currentTraceId(), Instant.now());
    }

    private static String currentTraceId() {
        String value = MDC.get("traceId");
        return value == null ? "unavailable" : value;
    }

    public record ApiError(String code, String message) {}
}
