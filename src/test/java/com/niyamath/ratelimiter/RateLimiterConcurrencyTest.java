package com.niyamath.ratelimiter;

import com.niyamath.ratelimiter.models.CheckResponse;
import com.niyamath.ratelimiter.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RateLimiterConcurrencyTest {

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void allowsExactLimitUnderHighConcurrency() throws InterruptedException {
        String clientId = "concurrent-user";
        String key = "ratelimit:fixed:" + clientId;
        redisTemplate.delete(key); // Start fresh

        int totalRequests = 100; // Firing 100 simultaneous requests (limit is 5)
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch startingGun = new CountDownLatch(1);
        CountDownLatch finishLine = new CountDownLatch(totalRequests);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startingGun.await(); // Every thread waits here until gun fires
                    CheckResponse response = rateLimiterService.check(clientId);
                    if (response.allowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        blockedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLine.countDown();
                }
            });
        }

        startingGun.countDown(); // Fire! All 50 threads slam Redis at once
        finishLine.await();      // Wait for all 100 requests to finish
        executor.shutdown();

        // Proof 1: Strictly 5 allowed, 95 blocked (zero leaks)
        assertEquals(5, allowedCount.get(), "Should allow strictly 5 requests");
        assertEquals(95, blockedCount.get(), "Should block the remaining 95 requests");

        // Proof 2: Key has an active TTL (not an immortal key)
        Long ttl = redisTemplate.getExpire(key);
        assertTrue(ttl != null && ttl > 0, "Redis key must have an active expiration");
    }


}