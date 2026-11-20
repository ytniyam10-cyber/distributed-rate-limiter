package com.niyamath.ratelimiter.strategy;

import com.niyamath.ratelimiter.models.CheckResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component("SLIDING_WINDOW")
public class SlidingWindowStrategy implements  RateLimiterStrategy{
    public StringRedisTemplate redisTemplate;
    public DefaultRedisScript<List> slidingwindowScript;

    public SlidingWindowStrategy(StringRedisTemplate redisTemplate,DefaultRedisScript<List>slidingwindowScript){
        this.redisTemplate=redisTemplate;
        this.slidingwindowScript=slidingwindowScript;
    }

    @Override
    public CheckResponse check(String clientId, long limit, long windowSeconds) {
        String key="ratelimit:sliding:" + clientId;
        long windowMs = windowSeconds * 1000L;
        long now=System.currentTimeMillis();
        String member=now + "-" + UUID.randomUUID();
        List<Long>result=redisTemplate.execute(slidingwindowScript, Collections.singletonList(key),
                String.valueOf(limit),String.valueOf(windowMs),String.valueOf(now), member);
        boolean allowed = result != null && result.get(0) == 1L;
        long remaining = (result != null && result.size() > 1) ? result.get(1) : 0L;

        return new CheckResponse(allowed, remaining,limit);
    }
}
