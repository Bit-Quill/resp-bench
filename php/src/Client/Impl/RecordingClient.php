<?php

declare(strict_types=1);

namespace RespBench\Client\Impl;

use RespBench\Client\BenchmarkClient;
use RespBench\Client\TimedResult;
use RespBench\Config\DriverConfig;

/**
 * In-memory recording client for server-free tests and pipeline validation.
 *
 * Stores keys/values in a local array and records every operation, so the full
 * engine path (key generation, command selection, metrics, NDJSON output) can be
 * exercised without a live server. Latency is a small synthetic value.
 */
final class RecordingClient extends BenchmarkClient
{
    /** @var array<string,string> */
    private array $store = [];

    /** @var list<array{op:string,key:string,size:int}> */
    private array $recorded = [];

    private bool $connected = false;

    public function connect(string $host, int $port, DriverConfig $config): void
    {
        $this->connected = true;
    }

    public function isConnected(): bool
    {
        return $this->connected;
    }

    public function ping(): TimedResult
    {
        return $this->measure(function (): string {
            $this->recorded[] = ['op' => 'PING', 'key' => '', 'size' => 0];

            return 'PONG';
        });
    }

    public function get(string $key): TimedResult
    {
        return $this->measure(function () use ($key): ?string {
            $this->recorded[] = ['op' => 'GET', 'key' => $key, 'size' => 0];

            return $this->store[$key] ?? null;
        });
    }

    public function set(string $key, string $value): TimedResult
    {
        return $this->measure(function () use ($key, $value): string {
            $this->store[$key] = $value;
            $this->recorded[] = ['op' => 'SET', 'key' => $key, 'size' => strlen($value)];

            return 'OK';
        });
    }

    public function del(string $key): TimedResult
    {
        return $this->measure(function () use ($key): int {
            $existed = isset($this->store[$key]);
            unset($this->store[$key]);
            $this->recorded[] = ['op' => 'DEL', 'key' => $key, 'size' => 0];

            return $existed ? 1 : 0;
        });
    }

    public function close(): void
    {
        $this->connected = false;
    }

    public function driverVersion(): string
    {
        return 'recording-1.0';
    }

    /**
     * @return list<array{op:string,key:string,size:int}>
     */
    public function recordedOperations(): array
    {
        return $this->recorded;
    }
}
