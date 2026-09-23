<?php

declare(strict_types=1);

namespace RespBench\Tests\Integration;

use PHPUnit\Framework\TestCase;
use RespBench\Client\BenchmarkClient;
use RespBench\Client\Factory;
use RespBench\Config\DriverConfig;
use RespBench\Config\Loader;
use RespBench\Engine\Benchmark;

/**
 * Live integration tests for the valkey-glide-php driver.
 *
 * These require BOTH:
 *   - the `valkey_glide` PHP extension loaded, and
 *   - a reachable Valkey/Redis server.
 *
 * Server endpoint via env vars (default localhost:6379):
 *   VALKEY_HOST, VALKEY_PORT
 *
 * When either prerequisite is missing the tests skip (they never fail in a
 * bare environment), matching the Ruby engine's live-server test behavior.
 */
final class LiveClientTest extends TestCase
{
    private string $host = 'localhost';
    private int $port = 6379;

    protected function setUp(): void
    {
        $this->host = getenv('VALKEY_HOST') ?: 'localhost';
        $this->port = (int) (getenv('VALKEY_PORT') ?: '6379');

        if (!extension_loaded('valkey_glide')) {
            self::markTestSkipped('valkey_glide extension not loaded');
        }
        if (!$this->serverReachable()) {
            self::markTestSkipped("Server not reachable at {$this->host}:{$this->port}");
        }
    }

    private function serverReachable(): bool
    {
        $errno = 0;
        $errstr = '';
        $conn = @fsockopen($this->host, $this->port, $errno, $errstr, 1.0);
        if ($conn === false) {
            return false;
        }
        fclose($conn);

        return true;
    }

    private function connect(): BenchmarkClient
    {
        $config = new DriverConfig(driverId: 'valkey-glide-php', mode: 'standalone');

        return Factory::createAndConnect($this->host, $this->port, $config);
    }

    public function testConnects(): void
    {
        $client = $this->connect();
        try {
            self::assertTrue($client->isConnected());
        } finally {
            $client->close();
        }
    }

    public function testPing(): void
    {
        $client = $this->connect();
        try {
            $result = $client->ping();
            self::assertTrue($result->isSuccess(), 'PING failed');
            self::assertGreaterThan(0, $result->latencyMicros);
        } finally {
            $client->close();
        }
    }

    public function testSetGetRoundTrip(): void
    {
        $client = $this->connect();
        try {
            $key = 'php-live-test-key';
            $value = 'value-' . time();

            $set = $client->set($key, $value);
            self::assertTrue($set->isSuccess(), 'SET failed');

            $get = $client->get($key);
            self::assertTrue($get->isSuccess(), 'GET failed');
            self::assertSame($value, $get->value);

            $client->del($key);
        } finally {
            $client->close();
        }
    }

    public function testDriverVersionIsNonEmpty(): void
    {
        $client = $this->connect();
        try {
            $version = $client->driverVersion();
            self::assertNotSame('', $version);
            self::assertNotSame('unknown', $version);
        } finally {
            $client->close();
        }
    }

    /**
     * End-to-end multi-process (fork) run against a live server. This is the key
     * fork-then-connect validation: N workers each open their own connection
     * AFTER forking. Asserts totals add up and latencies are realistic (> 0).
     */
    public function testMultiProcessLiveRun(): void
    {
        if (!function_exists('pcntl_fork')) {
            self::markTestSkipped('pcntl not available');
        }

        $metricsPath = sys_get_temp_dir() . '/resp_bench_php_live_' . uniqid('', true) . '.ndjson';

        $driver = new DriverConfig(driverId: 'valkey-glide-php', mode: 'standalone');
        $workload = Loader::loadWorkloadConfig(__DIR__ . '/../fixtures/smoke-workload.json');

        $engine = new Benchmark(
            host: $this->host,
            port: $this->port,
            driverConfig: $driver,
            workloadConfig: $workload,
            metricsPath: $metricsPath,
            commitId: 'live-test',
            concurrencyMode: 'process',
        );

        try {
            $engine->run();

            $lines = array_values(array_filter(explode("\n", (string) file_get_contents($metricsPath))));
            self::assertCount(2, $lines, 'Expected two phases');

            $phases = array_map(
                static fn (string $l): array => json_decode($l, true, 512, JSON_THROW_ON_ERROR),
                $lines,
            );

            // WARMUP: 400 requests, no errors.
            self::assertSame('WARMUP', $phases[0]['phase']['id']);
            self::assertSame(400, $phases[0]['totals']['requests']);
            self::assertSame(0, $phases[0]['totals']['errors']);

            // STEADY: 1000 requests total, no errors, GET+SET sum to total.
            self::assertSame('STEADY', $phases[1]['phase']['id']);
            self::assertSame(1000, $phases[1]['totals']['requests']);
            self::assertSame(0, $phases[1]['totals']['errors']);
            $sum = $phases[1]['metrics']['GET']['requests'] + $phases[1]['metrics']['SET']['requests'];
            self::assertSame(1000, $sum);

            // Real server latencies should be > 0 microseconds at some percentile.
            $getMax = $phases[1]['metrics']['GET']['latency']['summary']['max'];
            self::assertGreaterThan(0, $getMax, 'Expected non-zero GET latency against a live server');
        } finally {
            if (is_file($metricsPath)) {
                unlink($metricsPath);
            }
        }
    }
}
