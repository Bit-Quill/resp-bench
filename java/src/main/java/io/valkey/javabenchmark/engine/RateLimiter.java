/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Leaky bucket rate limiter that enforces a constant rate without burst.
 * 
 * <p>This implementation ensures operations are evenly spaced at the specified rate.
 * For example, with rps_limit=20, operations will be spaced ~50ms apart.</p>
 * 
 * <p>Unlike token bucket, there is no burst capacity - the rate is strictly enforced
 * from the first operation.</p>
 */
public class RateLimiter {
    private final int ratePerSecond;
    private final long intervalNanos;
    private final AtomicLong nextAllowedNanos;

    private RateLimiter(int ratePerSecond) {
        this.ratePerSecond = ratePerSecond;
        // Calculate interval between operations in nanoseconds
        // For 20 ops/sec: 1_000_000_000 / 20 = 50_000_000 ns = 50ms
        this.intervalNanos = 1_000_000_000L / ratePerSecond;
        // First operation is allowed immediately
        this.nextAllowedNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Create a rate limiter with the specified rate.
     * 
     * @param ratePerSecond operations per second (must be > 0)
     * @return rate limiter instance, or null if rate <= 0
     */
    public static RateLimiter create(int ratePerSecond) {
        return ratePerSecond > 0 ? new RateLimiter(ratePerSecond) : null;
    }

    /**
     * Acquire permission for one operation, blocking until allowed.
     * Operations are evenly spaced at the configured rate.
     * 
     * @throws InterruptedException if interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        while (true) {
            long now = System.nanoTime();
            long next = nextAllowedNanos.get();
            
            if (now >= next) {
                // Try to claim this slot and advance to next
                if (nextAllowedNanos.compareAndSet(next, next + intervalNanos)) {
                    return; // Successfully acquired
                }
                // Another thread claimed it, retry
            } else {
                // Need to wait until next allowed time
                long waitNanos = next - now;
                long waitMs = waitNanos / 1_000_000;
                int waitNanoRemainder = (int) (waitNanos % 1_000_000);
                
                if (waitMs > 0 || waitNanoRemainder > 0) {
                    Thread.sleep(waitMs, waitNanoRemainder);
                }
            }
        }
    }

    /**
     * Try to acquire permission for one operation without blocking.
     * 
     * @return true if acquired, false if rate limit would be exceeded
     */
    public boolean tryAcquire() {
        long now = System.nanoTime();
        long next = nextAllowedNanos.get();
        
        if (now >= next) {
            return nextAllowedNanos.compareAndSet(next, next + intervalNanos);
        }
        return false;
    }

    /**
     * Get the configured rate.
     * 
     * @return operations per second
     */
    public int getRatePerSecond() {
        return ratePerSecond;
    }
}