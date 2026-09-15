<?php

declare(strict_types=1);

namespace RespBench\Tests\Integration;

use PHPUnit\Framework\TestCase;
use RespBench\Config\Loader;
use RespBench\Engine\Benchmark;

final class RecordingWorkloadTest extends TestCase
{
    private string $metricsPath = '';

    protected function setUp(): void
    {
        $this->metricsPath = sys_get_temp_dir() . '/resp_bench_php_test_' . uniqid('', true) . '.ndjson';
    }

    protected function tearDown(): void
    {
        if ($this->metricsPath !== '' && is_file($this->metricsPath)) {
            unlink($this->metricsPath);
        }
    }

    private function runWorkload(string $mode): array
    {
        $fixtures = __DIR__ . '/../fixtures';
        $driver = Loader::loadDriverConfig($fixtures . '/recording-driver.json');
        $workload = Loader::loadWorkloadConfig($fixtures . '/smoke-workload.json');

        $engine = new Benchmark(
            host: 'localhost',
            port: 6379,
            driverConfig: $driver,
            workloadConfig: $workload,
            metricsPath: $this->metricsPath,
            commitId: 'testsha',
            concurrencyMode: $mode,
        );
        $engine->run();

        $lines = array_values(array_filter(explode("\n", (string) file_get_contents($this->metricsPath))));

        return array_map(
            static fn (string $line): array => json_decode($line, true, 512, JSON_THROW_ON_ERROR),
            $lines,
        );
    }

    public function testInlineModeProducesValidNdjson(): void
    {
        $phases = $this->runWorkload('inline');
        $this->assertSchema($phases);
    }

    public function testProcessModeProducesValidNdjson(): void
    {
        if (!function_exists('pcntl_fork')) {
            self::markTestSkipped('pcntl not available');
        }

        $phases = $this->runWorkload('process');
        $this->assertSchema($phases);
    }

    public function testInlineAndProcessAgreeOnTotals(): void
    {
        if (!function_exists('pcntl_fork')) {
            self::markTestSkipped('pcntl not available');
        }

        $inline = $this->runWorkload('inline');
        // Reset output between runs.
        unlink($this->metricsPath);
        $process = $this->runWorkload('process');

        // Totals are deterministic (request-based completion), independent of mode.
        foreach ([0, 1] as $i) {
            self::assertSame(
                $inline[$i]['totals']['requests'],
                $process[$i]['totals']['requests'],
                "Phase {$i} request totals differ between modes",
            );
        }
    }

    private function assertSchema(array $phases): void
    {
        self::assertCount(2, $phases, 'Expected two phases (WARMUP, STEADY)');

        // Phase 0: WARMUP, 400 requests, all SET.
        $warmup = $phases[0];
        self::assertSame('WARMUP', $warmup['phase']['id']);
        self::assertSame('COMPLETED', $warmup['phase']['status']);
        self::assertSame(400, $warmup['totals']['requests']);
        self::assertSame(0, $warmup['totals']['errors']);
        self::assertArrayHasKey('SET', $warmup['metrics']);

        // Metadata
        self::assertSame('testsha', $warmup['metadata']['commit_id']);
        self::assertSame('recording', $warmup['metadata']['driver_id']);

        // Phase 1: STEADY, 1000 requests total, GET + SET.
        $steady = $phases[1];
        self::assertSame('STEADY', $steady['phase']['id']);
        self::assertSame(1000, $steady['totals']['requests']);
        self::assertArrayHasKey('GET', $steady['metrics']);
        self::assertArrayHasKey('SET', $steady['metrics']);

        // GET + SET request counts sum to the total.
        $sum = $steady['metrics']['GET']['requests'] + $steady['metrics']['SET']['requests'];
        self::assertSame(1000, $sum);

        // Latency block schema.
        $latency = $steady['metrics']['GET']['latency'];
        self::assertSame('us', $latency['unit']);
        self::assertArrayHasKey('summary', $latency);
        foreach (['min', 'p50', 'p95', 'p99', 'p999', 'max'] as $k) {
            self::assertArrayHasKey($k, $latency['summary']);
            self::assertIsInt($latency['summary'][$k]);
        }
        self::assertSame('hdr', $latency['hdr']['format']);
        self::assertSame(3, $latency['hdr']['sigfig']);
        self::assertNotSame('', $latency['hdr']['payload_b64']);
    }
}
