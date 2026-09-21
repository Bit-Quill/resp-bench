<?php

declare(strict_types=1);

namespace RespBench\Tests\Integration;

use PHPUnit\Framework\TestCase;

/**
 * Black-box rate-limiting integration tests, mirroring the Java/Ruby
 * RateLimitingTest. Uses the recording driver (no network latency), so rate
 * limiting is the dominant factor in execution time.
 *
 * Tolerance is generous (10%) to stay reliable on shared CI runners while still
 * proving the limiter works.
 */
final class RateLimitingTest extends TestCase
{
    use EngineTestTrait;

    private const RATE_TOLERANCE = 0.10;

    public function testRpsLimitWithRequestBasedCompletion(): void
    {
        $targetRps = 50;
        $targetRequests = 100;
        $expectedDurationMs = ($targetRequests * 1000) / $targetRps; // ~2000ms

        $workload = $this->parseWorkload($this->rpsWorkload($targetRps, $targetRequests));
        $phases = $this->runEngine($workload);
        $phase = $phases[0];

        // Exact request count.
        self::assertSame($targetRequests, $phase['totals']['requests']);

        $durationMs = $phase['phase']['duration_ms'];
        self::assertEqualsWithDelta(
            $expectedDurationMs,
            $durationMs,
            $expectedDurationMs * self::RATE_TOLERANCE,
            "Duration {$durationMs}ms should be ~{$expectedDurationMs}ms",
        );

        $actualRate = $phase['totals']['requests'] / ($durationMs / 1000.0);
        self::assertEqualsWithDelta($targetRps, $actualRate, $targetRps * self::RATE_TOLERANCE);
    }

    public function testSharedRpsLimitAcrossConnections(): void
    {
        // 50 rps shared across 4 connections. This only behaves as a *shared*
        // limit under real concurrency, so run in process (fork) mode where the
        // 4 workers execute in parallel. Each worker gets 50/4 rps and 100/4
        // requests -> ~2s wall clock, not 4x that.
        if (!function_exists('pcntl_fork')) {
            self::markTestSkipped('pcntl not available (shared-rate semantics need concurrent workers)');
        }

        $targetRps = 50;
        $connections = 4;
        $targetRequests = 100;
        $expectedDurationMs = ($targetRequests * 1000) / $targetRps; // ~2000ms

        $workload = $this->parseWorkload($this->rpsWorkload($targetRps, $targetRequests, $connections));

        $metricsPath = sys_get_temp_dir() . '/resp_bench_php_sharedrps_' . uniqid('', true) . '.ndjson';
        $engine = new \RespBench\Engine\Benchmark(
            host: 'localhost',
            port: 6379,
            driverConfig: $this->recordingDriver(),
            workloadConfig: $workload,
            metricsPath: $metricsPath,
            commitId: 'it-test',
            concurrencyMode: 'process',
        );

        $start = hrtime(true);
        $engine->run();
        $wallMs = (hrtime(true) - $start) / 1_000_000;

        $line = trim((string) file_get_contents($metricsPath));
        unlink($metricsPath);
        $phase = json_decode($line, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame($connections, $phase['phase']['connections']);
        self::assertSame($targetRequests, $phase['totals']['requests']);

        // Concurrent workers -> aggregate honors the shared limit: wall clock is
        // ~2s, not ~8s. Allow generous tolerance for fork + CI jitter.
        self::assertGreaterThan(
            $expectedDurationMs * 0.7,
            $wallMs,
            "Wall clock {$wallMs}ms too fast — shared limit not enforced",
        );
        self::assertLessThan(
            $expectedDurationMs * 2.5,
            $wallMs,
            "Wall clock {$wallMs}ms too slow — workers not running concurrently (shared limit)",
        );
    }

    public function testNoRateLimitAllowsMaximumThroughput(): void
    {
        $targetRequests = 5000;
        $workload = $this->parseWorkload($this->rpsWorkload(-1, $targetRequests));
        $phases = $this->runEngine($workload);
        $phase = $phases[0];

        self::assertSame($targetRequests, $phase['totals']['requests']);
        $durationMs = $phase['phase']['duration_ms'];
        self::assertLessThan(1000, $durationMs, "Unlimited run should be fast (<1s), was {$durationMs}ms");
    }

    public function testUnlimitedMuchFasterThanRateLimited(): void
    {
        $targetRequests = 100;
        $rps = 50;

        $limited = $this->runEngine($this->parseWorkload($this->rpsWorkload($rps, $targetRequests)));
        $unlimited = $this->runEngine($this->parseWorkload($this->rpsWorkload(-1, $targetRequests)));

        $limitedMs = $limited[0]['phase']['duration_ms'];
        $unlimitedMs = $unlimited[0]['phase']['duration_ms'];

        $expectedLimitedMs = ($targetRequests * 1000) / $rps; // ~2000ms
        self::assertEqualsWithDelta($expectedLimitedMs, $limitedMs, $expectedLimitedMs * self::RATE_TOLERANCE);

        // Unlimited should be dramatically faster.
        self::assertLessThan(
            $limitedMs / 5,
            $unlimitedMs,
            "Unlimited ({$unlimitedMs}ms) should be far faster than rate-limited ({$limitedMs}ms)",
        );
    }

    public function testRpsLimitBelowConnectionCountDoesNotOvershoot(): void
    {
        // Regression: the old max(1, intdiv(rps, N)) floor turned a rounded-to-zero
        // share into 1 rps per worker, multiplying back up above the target
        // (rps=4, connections=8 → 8 rps aggregate). The remainder-distribution
        // split gives 4 workers 1 rps and 4 workers 0, summing to exactly 4.
        if (!function_exists('pcntl_fork')) {
            self::markTestSkipped('pcntl not available');
        }

        $targetRps = 4;
        $connections = 8;
        $targetRequests = 20;

        $metricsPath = null;
        $workload = $this->parseWorkload($this->rpsWorkload($targetRps, $targetRequests, $connections));
        $phase = $this->runEngineProcess($workload, $metricsPath)[0];

        self::assertSame($targetRequests, $phase['totals']['requests']);

        $durationMs = $phase['phase']['duration_ms'];
        $actualRate = $phase['totals']['requests'] / ($durationMs / 1000.0);

        // Aggregate must not exceed the target by more than tolerance. The old
        // floored behavior would double it to ~8 rps.
        self::assertLessThan(
            $targetRps * 1.5,
            $actualRate,
            "Aggregate rate {$actualRate} overshoots target {$targetRps} (rps < connections floor bug)",
        );
    }

    private function rpsWorkload(int $rpsLimit, int $requests, int $connections = 1): string
    {
        return <<<JSON
        {
            "benchmark_profile": {"name": "RpsTest"},
            "phases": [{
                "id": "RPS",
                "connections": {$connections},
                "rps_limit": {$rpsLimit},
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "rps:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {$requests}}
            }]
        }
        JSON;
    }
}
