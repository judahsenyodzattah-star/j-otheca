package com.jotheca.common.error;

import java.time.Instant;
import java.util.Map;

/** Consistent API error envelope (spec §40: avoid leaking internals; stable codes for clients). */
public record ApiError(Instant timestamp, int status, String code, String message,
                       Map<String, Object> details, String traceId) {

    public static ApiError of(int status, String code, String message) {
        return new ApiError(Instant.now(), status, code, message, Map.of(), newTrace());
    }

    public static ApiError of(int status, String code, String message, Map<String, Object> details) {
        return new ApiError(Instant.now(), status, code, message, details, newTrace());
    }

    private static String newTrace() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
