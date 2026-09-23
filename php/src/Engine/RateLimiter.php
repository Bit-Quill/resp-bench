<?php

declare(strict_types=1);

namespace RespBench\Engine;

/**
 * Leaky-bucket rate limiter that enforces a constant rate without burst.
 *
 * Operations are evenly spaced at the configured rate (e.g. rps=20 → ~50ms apart).
 * Matches the Java/Ruby reference behavior.
 *
 * In the multi-process engine each worker owns its own limiter; the engine divides
 * the phase-level rate across workers so the aggregate matches the target.
 */
final class RateLimiter
{
    private readonly int $intervalNanos;
    private int $nextAllowedNanos;

    private function __construct(public readonly int $ratePerSecond)
    {
        // Nanoseconds between operations. For 20 ops/s: 1e9 / 20 = 50ms.
        $this->intervalNanos = intdiv(1_000_000_000, $ratePerSecond);
        $this->nextAllowedNanos = self::monotonicNanos();
    }

    /**
     * @return self|null null if the rate is unlimited (<= 0)
     */
    public static function create(int $ratePerSecond): ?self
    {
        if ($ratePerSecond <= 0) {
            return null;
        }

        return new self($ratePerSecond);
    }

    /**
     * Block until one operation is allowed.
     */
    public function acquire(): void
    {
        while (true) {
            $now = self::monotonicNanos();
            if ($now >= $this->nextAllowedNanos) {
                $this->nextAllowedNanos += $this->intervalNanos;

                return;
            }

            $waitNanos = $this->nextAllowedNanos - $now;
            // usleep takes microseconds.
            $micros = intdiv($waitNanos, 1000);
            if ($micros > 0) {
                usleep($micros);
            }
        }
    }

    /**
     * Try to acquire without blocking.
     */
    public function tryAcquire(): bool
    {
        $now = self::monotonicNanos();
        if ($now < $this->nextAllowedNanos) {
            return false;
        }
        $this->nextAllowedNanos += $this->intervalNanos;

        return true;
    }

    private static function monotonicNanos(): int
    {
        return (int) hrtime(true);
    }
}
