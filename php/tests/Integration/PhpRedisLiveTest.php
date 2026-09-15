<?php

declare(strict_types=1);

namespace RespBench\Tests\Integration;

use PHPUnit\Framework\TestCase;
use RespBench\Client\BenchmarkClient;
use RespBench\Client\Factory;
use RespBench\Config\DriverConfig;

/**
 * Live integration tests for the phpredis driver (ext-redis).
 *
 * Requires BOTH the `redis` extension and a reachable server; skips cleanly
 * otherwise. Server endpoint via VALKEY_HOST / VALKEY_PORT (default localhost:6379).
 */
final class PhpRedisLiveTest extends TestCase
{
    private string $host = 'localhost';
    private int $port = 6379;

    protected function setUp(): void
    {
        $this->host = getenv('VALKEY_HOST') ?: 'localhost';
        $this->port = (int) (getenv('VALKEY_PORT') ?: '6379');

        if (!extension_loaded('redis')) {
            self::markTestSkipped('redis (phpredis) extension not loaded');
        }
        if (!$this->serverReachable()) {
            self::markTestSkipped("Server not reachable at {$this->host}:{$this->port}");
        }
    }

    private function serverReachable(): bool
    {
        $conn = @fsockopen($this->host, $this->port, $errno, $errstr, 1.0);
        if ($conn === false) {
            return false;
        }
        fclose($conn);

        return true;
    }

    private function connect(): BenchmarkClient
    {
        return Factory::createAndConnect(
            $this->host,
            $this->port,
            new DriverConfig(driverId: 'phpredis', mode: 'standalone'),
        );
    }

    public function testConnectsAndPings(): void
    {
        $client = $this->connect();
        try {
            self::assertTrue($client->isConnected());
            $ping = $client->ping();
            self::assertTrue($ping->isSuccess());
        } finally {
            $client->close();
        }
    }

    public function testSetGetRoundTrip(): void
    {
        $client = $this->connect();
        try {
            $key = 'phpredis-live-test-key';
            $value = 'value-' . time();

            self::assertTrue($client->set($key, $value)->isSuccess());

            $get = $client->get($key);
            self::assertTrue($get->isSuccess());
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
}
