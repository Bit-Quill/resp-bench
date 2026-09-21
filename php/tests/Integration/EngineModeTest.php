<?php

declare(strict_types=1);

namespace RespBench\Tests\Integration;

use PHPUnit\Framework\TestCase;
use RespBench\Config\DriverConfig;
use RespBench\Config\Loader;
use RespBench\Engine\Benchmark;
use RuntimeException;

/**
 * Tests for concurrency-mode resolution and phase-status propagation — the
 * "fail loudly instead of silently misreporting" guarantees.
 */
final class EngineModeTest extends TestCase
{
    private function workload(int $connections, string $completionType = 'requests'): string
    {
        $completion = $completionType === 'duration'
            ? '{"type": "duration", "seconds": 1}'
            : '{"type": "requests", "requests": 10}';

        return <<<JSON
        {
            "benchmark_profile": {"name": "ModeTest"},
            "phases": [{
                "id": "P",
                "connections": {$connections},
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 16}],
                "keyspace": {"key_prefix": "m:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {$completion}
            }]
        }
        JSON;
    }

    private function runEngineStatus(DriverConfig $driver, string $workloadJson, ?string $mode): int
    {
        $path = sys_get_temp_dir() . '/resp_bench_mode_' . uniqid('', true) . '.ndjson';
        try {
            $engine = new Benchmark(
                host: 'localhost',
                port: 6379,
                driverConfig: $driver,
                workloadConfig: Loader::parseWorkloadConfigString($workloadJson),
                metricsPath: $path,
                commitId: 'mode-test',
                concurrencyMode: $mode,
            );

            return $engine->run();
        } finally {
            if (is_file($path)) {
                unlink($path);
            }
        }
    }

    public function testInvalidConcurrencyModeRejected(): void
    {
        $this->expectException(RuntimeException::class);
        $this->expectExceptionMessage('Invalid --concurrency');
        $this->runEngineStatus(
            new DriverConfig(driverId: 'recording', mode: 'standalone'),
            $this->workload(1),
            'proccess', // typo
        );
    }

    public function testInlineRejectedForRealDriverMultiConnection(): void
    {
        // A real (non-recording) driver with connections > 1 must not run inline,
        // because serial execution would misreport throughput.
        $this->expectException(RuntimeException::class);
        $this->expectExceptionMessage('Refusing --concurrency inline');
        $this->runEngineStatus(
            new DriverConfig(driverId: 'valkey-glide-php', mode: 'standalone'),
            $this->workload(4),
            'inline',
        );
    }

    public function testRecordingInlineMultiConnectionAllowed(): void
    {
        // Recording driver is server-free; inline with N connections is fine.
        $status = $this->runEngineStatus(
            new DriverConfig(driverId: 'recording', mode: 'standalone'),
            $this->workload(4),
            'inline',
        );
        self::assertSame(0, $status);
    }

    public function testUnsupportedPipelineDepthRejected(): void
    {
        $json = <<<JSON
        {
            "benchmark_profile": {"name": "PD"},
            "phases": [{
                "id": "P",
                "connections": 1,
                "pipeline_depth": 8,
                "commands": [{"command": "get", "weight": 1.0}],
                "keyspace": {"keys_count": 100},
                "completion": {"type": "requests", "requests": 10}
            }]
        }
        JSON;

        $this->expectException(RuntimeException::class);
        $this->expectExceptionMessage('pipeline_depth');
        $this->runEngineStatus(new DriverConfig(driverId: 'recording', mode: 'standalone'), $json, 'inline');
    }

    public function testUnsupportedCpsLimitRejected(): void
    {
        $json = <<<JSON
        {
            "benchmark_profile": {"name": "CPS"},
            "phases": [{
                "id": "P",
                "connections": 1,
                "cps_limit": 10,
                "commands": [{"command": "get", "weight": 1.0}],
                "keyspace": {"keys_count": 100},
                "completion": {"type": "requests", "requests": 10}
            }]
        }
        JSON;

        $this->expectException(RuntimeException::class);
        $this->expectExceptionMessage('cps_limit');
        $this->runEngineStatus(new DriverConfig(driverId: 'recording', mode: 'standalone'), $json, 'inline');
    }

    public function testCommandTimeoutKnobRejected(): void
    {
        $this->expectException(RuntimeException::class);
        $this->expectExceptionMessage('command_timeout_ms');
        $this->runEngineStatus(
            new DriverConfig(
                driverId: 'recording',
                mode: 'standalone',
                specificDriverConfig: ['command_timeout_ms' => 10000],
            ),
            $this->workload(1),
            'inline',
        );
    }

    public function testWorkerFailureYieldsErrorStatusAndNonZeroExit(): void
    {
        if (!function_exists('pcntl_fork')) {
            self::markTestSkipped('pcntl not available');
        }

        // 100% error rate is a successful workload (errors are recorded, not thrown),
        // so to force *worker* failure we point phpredis at a driver whose extension
        // is absent — connect() throws, the child exits non-zero, phase => ERROR.
        if (extension_loaded('redis')) {
            self::markTestSkipped('redis extension present; cannot force connect failure');
        }

        $path = sys_get_temp_dir() . '/resp_bench_fail_' . uniqid('', true) . '.ndjson';
        try {
            $engine = new Benchmark(
                host: 'localhost',
                port: 6379,
                driverConfig: new DriverConfig(driverId: 'phpredis', mode: 'standalone'),
                workloadConfig: Loader::parseWorkloadConfigString($this->workload(4)),
                metricsPath: $path,
                commitId: 'fail-test',
                concurrencyMode: 'process',
            );
            $status = $engine->run();

            self::assertSame(1, $status, 'exit status must be non-zero when workers fail');

            $line = trim((string) file_get_contents($path));
            $phase = json_decode($line, true, 512, JSON_THROW_ON_ERROR);
            self::assertSame('ERROR', $phase['phase']['status']);
            // metrics must serialize as an object, never [].
            self::assertStringNotContainsString('"metrics":[]', $line);
        } finally {
            if (is_file($path)) {
                unlink($path);
            }
        }
    }
}
