package com.niyamath.ratelimiter.service;

import com.niyamath.ratelimiter.models.CheckResponse;
import com.niyamath.ratelimiter.strategy.RateLimiterStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RateLimiterService {
    private static final long LIMIT = 5;
    private static final long WINDOW = 60;
    private final Map<String, RateLimiterStrategy> strategies;
    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    private final boolean failOpen;

    public RateLimiterService(Map<String, RateLimiterStrategy> strategies, @Value("${ratelimiter.fail-open:true}") boolean failOpen) {
        this.strategies = strategies;
        this.failOpen=failOpen;
    }
    public CheckResponse check(String clientId){
        return check(clientId,"SLIDING_WINDOW",LIMIT,WINDOW);

    }
    public CheckResponse check(String clientId, String algorithm, long limit, long windowSeconds){
        String key=(algorithm==null || algorithm.isBlank())?"SLIDING_WINDOW": algorithm.toUpperCase();
        RateLimiterStrategy strategy=strategies.get(key);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported rate limiter algorithm: " + algorithm);
        }

        try {
            return strategy.check(clientId, limit, windowSeconds);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during rate limit check for client '{}'. Applying failOpen={}: {}",
                    clientId, failOpen, ex.getMessage());
            long fallbackRemaining = failOpen ? limit : 0L;
            return new CheckResponse(failOpen, fallbackRemaining, limit);
        }
    }
}
