package com.niyamath.ratelimiter.models;

import io.swagger.v3.oas.annotations.media.Schema;

public record CheckRequest(
        @Schema(description = "Unique identifier for the client", example = "user-123")
        String clientId,
        @Schema(description = "Rate limiting strategy (SLIDING_WINDOW or FIXED_WINDOW)", example = "SLIDING_WINDOW")
        String algorithm) {}
