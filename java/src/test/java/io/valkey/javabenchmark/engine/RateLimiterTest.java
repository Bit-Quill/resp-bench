/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link RateLimiter} class.
 * 
 * <p>RateLimiter uses a leaky bucket algorithm that enforces a constant rate
 * without burst. Operations are evenly spaced at the configured rate.</p>
 */
class RateLimiterTest {

    @Test
    void createReturnsNullForNegativeRate() {
        RateLimiter limiter = RateLimiter.create(-1);
        assertThat(limiter).isNull();
    }

    @Test
    void createReturnsNullForZeroRate() {
        RateLimiter limiter = RateLimiter.create(0);
        assertThat(limiter).isNull();
    }

    @Test
    void createReturnsInstanceForPositiveRate() {
        RateLimiter limiter = RateLimiter.create(100);
        assertThat(limiter).isNotNull();
    }

    @Test
    void getRatePerSecondReturnsConfiguredRate() {
        RateLimiter limiter = RateLimiter.create(42);
        assertThat(limiter.getRatePerSecond()).isEqualTo(42);
    }

    @Test
    void tryAcquireSucceedsOnFirstCall() {
        RateLimiter limiter = RateLimiter.create(20);
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void tryAcquireFailsImmediatelyAfterFirstCall() {
        // With leaky bucket, second call should fail if made immediately
        RateLimiter limiter = RateLimiter.create(20); // 50ms interval
        
        assertThat(limiter.tryAcquire()).isTrue();  // First succeeds
        assertThat(limiter.tryAcquire()).isFalse(); // Second fails (no time elapsed)
    }

    @Test
    void tryAcquireSucceedsAfterInterval() throws Exception {
        int rate = 20; // 50ms interval
        RateLimiter limiter = RateLimiter.create(rate);
        
        assertThat(limiter.tryAcquire()).isTrue();  // First succeeds
        
        // Wait for interval
        Thread.sleep(55); // slightly more than 50ms
        
        assertThat(limiter.tryAcquire()).isTrue();  // Should succeed after interval
    }

    @Test
    void acquireEnforcesConstantRate() throws Exception {
        int rate = 20; // 20 ops/sec = 50ms interval
        RateLimiter limiter = RateLimiter.create(rate);
        
        long startTime = System.currentTimeMillis();
        
        // Acquire 5 operations
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        
        long durationMs = System.currentTimeMillis() - startTime;
        
        // 5 ops at 20/sec with first immediate: 4 intervals of 50ms = 200ms
        // Allow some tolerance for timing variability
        assertThat(durationMs).isGreaterThanOrEqualTo(180L);
    }

    @Test
    void acquireMaintainsRateOverTime() throws Exception {
        int rate = 20; // 20 ops/sec
        RateLimiter limiter = RateLimiter.create(rate);
        
        long startTime = System.currentTimeMillis();
        int count = 0;
        
        // Run for 1 second
        while (System.currentTimeMillis() - startTime < 1000) {
            limiter.acquire();
            count++;
        }
        
        // Should have executed approximately 20 operations
        // With 5% tolerance: 19-21
        assertThat(count).isBetween(19, 21);
    }

    @Test
    void lowRateCreatesSignificantDelay() throws Exception {
        int rate = 5; // 5 ops/sec = 200ms interval
        RateLimiter limiter = RateLimiter.create(rate);
        
        long startTime = System.currentTimeMillis();
        
        // 3 operations: first immediate, then 2 waits of 200ms
        for (int i = 0; i < 3; i++) {
            limiter.acquire();
        }
        
        long durationMs = System.currentTimeMillis() - startTime;
        
        // Should take ~400ms (2 intervals of 200ms)
        assertThat(durationMs).isGreaterThanOrEqualTo(380L);
    }
}