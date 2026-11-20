package com.niyamath.ratelimiter.strategy;

import com.niyamath.ratelimiter.models.CheckResponse;


public interface RateLimiterStrategy {
    CheckResponse check(String clientId, long limit, long windowSeconds);
}
