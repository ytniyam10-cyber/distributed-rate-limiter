package com.niyamath.ratelimiter.strategy;

import com.niyamath.ratelimiter.models.CheckResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("FIXED_WINDOW")
public class FixedWindowStrategy implements RateLimiterStrategy{
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> fixedWindowScript;
    public FixedWindowStrategy(StringRedisTemplate redisTemplate,DefaultRedisScript<Long> fixedWindowScript){
        this.fixedWindowScript=fixedWindowScript;
        this.redisTemplate=redisTemplate;
    }
    @Override
    public CheckResponse check(String clientId, long limit, long windowSeconds) {
        String key = "ratelimit:fixed:" + clientId;
        Long count = redisTemplate.execute(
                fixedWindowScript,
                List.of(key),
                String.valueOf(windowSeconds)
        );
        long used = (count == null) ? 0 : count;
        long remaining=Math.max(0,limit-used);
        boolean allowed=used<=limit;
        return new CheckResponse(allowed,remaining,limit);
    }
}
