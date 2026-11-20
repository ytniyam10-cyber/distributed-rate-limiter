package com.niyamath.ratelimiter.models;

public record CheckResponse(boolean allowed, long remaining, long limit) {}