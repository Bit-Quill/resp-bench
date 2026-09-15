<?php

declare(strict_types=1);

namespace RespBench\Tests\Unit;

use PHPUnit\Framework\TestCase;
use RespBench\Engine\RateLimiter;

final class RateLimiterTest extends TestCase
{
    public function testUnlimitedReturnsNull(): void
    {
        self::assertNull(RateLimiter::create(0));
        self::assertNull(RateLimiter::create(-1));
    }

    public function testCreatePositiveRate(): void
    {
        $limiter = RateLimiter::create(100);
        self::assertNotNull($limiter);
        self::assertSame(100, $limiter->ratePerSecond);
    }

    public function testEnforcesApproximateRate(): void
    {
        // 200 ops/sec -> 30 ops should take ~145ms (29 intervals of 5ms).
        $limiter = RateLimiter::create(200);
        self::assertNotNull($limiter);

        $start = hrtime(true);
        for ($i = 0; $i < 30; $i++) {
            $limiter->acquire();
        }
        $elapsedMs = (hrtime(true) - $start) / 1_000_000;

        // Expected ~145ms; allow generous tolerance for CI timing jitter.
        self::assertGreaterThan(100, $elapsedMs, "Rate limiter too fast: {$elapsedMs}ms");
        self::assertLessThan(400, $elapsedMs, "Rate limiter too slow: {$elapsedMs}ms");
    }

    public function testTryAcquireRespectsInterval(): void
    {
        $limiter = RateLimiter::create(1); // 1 op/sec
        self::assertNotNull($limiter);

        // First is immediately allowed.
        self::assertTrue($limiter->tryAcquire());
        // Second should be denied (1s not elapsed).
        self::assertFalse($limiter->tryAcquire());
    }
}
