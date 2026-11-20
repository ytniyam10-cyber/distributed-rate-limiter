package com.niyamath.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.niyamath.ratelimiter.service.RateLimiterService;

@SpringBootTest
class BoundaryBurstTest {

    private static final long LIMIT = 5;
    private static final long WINDOW_SECONDS = 3;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Test
    void fixedWindowLeaksAtBoundaryButSlidingWindowDoesNot() throws InterruptedException {
        String fixedId = "burst-fixed-" + UUID.randomUUID();
        String slidingId = "burst-sliding-" + UUID.randomUUID();

        // t = 0s: one request starts the window
        send("FIXED_WINDOW", fixedId, 1);
        send("SLIDING_WINDOW", slidingId, 1);

        // t = 2.5s: near the end of the window, use up the rest of the limit
        Thread.sleep(2500);
        int fixedEarly = send("FIXED_WINDOW", fixedId, 4);
        int slidingEarly = send("SLIDING_WINDOW", slidingId, 4);

        // t = 3.2s: the fixed window's key has just expired
        Thread.sleep(700);
        int fixedLate = send("FIXED_WINDOW", fixedId, 5);
        int slidingLate = send("SLIDING_WINDOW", slidingId, 5);

        System.out.println("Allowed in ~0.7s around the boundary:");
        System.out.println("  Fixed window:   " + (fixedEarly + fixedLate));
        System.out.println("  Sliding window: " + (slidingEarly + slidingLate));

        assertEquals(5, fixedLate, "fixed window forgot the earlier burst");
        assertEquals(1, slidingLate, "sliding window remembered the earlier burst");
    }

    private int send(String algorithm, String clientId, int times) {
        int allowed = 0;
        for (int i = 0; i < times; i++) {
            if (rateLimiterService.check(clientId, algorithm, LIMIT, WINDOW_SECONDS).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }
}