<?php

declare(strict_types=1);

namespace RespBench\Tests\Integration;

use PHPUnit\Framework\TestCase;
use RespBench\Config\DriverConfig;
use RespBench\Engine\Benchmark;

/**
 * Black-box error-metrics integration tests (mirrors the Java/Ruby
 * ErrorMetricsIntegrationTest). Uses the recording driver's error-simulation
 * (`error_rate` in specific_driver_config) to drive failures through the real
 * engine → collector → NDJSON pipeline.
 */
final class ErrorMetricsTest extends TestCase
{
    public function testFullErrorRateProducesAllErrors(): void
    {
        $phase = $this->runWithErrorRate(1.0, 200);

        self::assertSame(200, $phase['totals']['requests']);
        self::assertSame(200, $phase['totals']['errors']);
        // Per-command error count matches.
        self::assertSame(200, $phase['metrics']['SET']['requests']);
        self::assertSame(200, $phase['metrics']['SET']['errors']);
    }

    public function testZeroErrorRateProducesNoErrors(): void
    {
        $phase = $this->runWithErrorRate(0.0, 200);

        self::assertSame(200, $phase['totals']['requests']);
        self::assertSame(0, $phase['totals']['errors']);
        self::assertSame(0, $phase['metrics']['SET']['errors']);
    }

    public function testPartialErrorRateIsApproximatelyHonored(): void
    {
        $target = 0.25;
        $requests = 4000;
        $phase = $this->runWithErrorRate($target, $requests);

        self::assertSame($requests, $phase['totals']['requests']);

        $errorFraction = $phase['totals']['errors'] / $requests;
        // Statistical: expect ~25%, allow +/- 5 points.
        self::assertEqualsWithDelta($target, $errorFraction, 0.05);
    }

    public function testErrorsExcludedFromLatencyHistogramCount(): void
    {
        // With a 100% error rate, no successful latencies are recorded, so the
        // per-command latency count is 0 while requests/errors are full.
        $phase = $this->runWithErrorRate(1.0, 100);

        self::assertSame(100, $phase['metrics']['SET']['requests']);
        self::assertSame(100, $phase['metrics']['SET']['errors']);
        self::assertSame(0, $phase['metrics']['SET']['latency']['count']);
    }

    /**
     * @return array<string,mixed> the single phase object
     */
    private function runWithErrorRate(float $errorRate, int $requests): array
    {
        $driver = new DriverConfig(
            driverId: 'recording',
            mode: 'standalone',
            specificDriverConfig: ['error_rate' => $errorRate, 'error_message' => 'Simulated failure'],
        );

        $workload = \RespBench\Config\Loader::parseWorkloadConfigString(<<<JSON
        {
            "benchmark_profile": {"name": "ErrorTest"},
            "phases": [{
                "id": "ERRORS",
                "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "err:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {$requests}}
            }]
        }
        JSON);

        $metricsPath = sys_get_temp_dir() . '/resp_bench_php_err_' . uniqid('', true) . '.ndjson';
        $engine = new Benchmark(
            host: 'localhost',
            port: 6379,
            driverConfig: $driver,
            workloadConfig: $workload,
            metricsPath: $metricsPath,
            commitId: 'err-test',
            concurrencyMode: 'inline',
        );
        $engine->run();

        $line = trim((string) file_get_contents($metricsPath));
        unlink($metricsPath);

        return json_decode($line, true, 512, JSON_THROW_ON_ERROR);
    }
}
