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

    // Operation recording is OFF by default: in a real benchmark the $recorded
    // log would grow by one entry per request with nothing draining it,
    // exhausting memory on long/duration-based phases. Tests that need the log
    // opt in via specific_driver_config.record_operations = true.
    private bool $recordOps = false;

    private float $errorRate = 0.0;
    private string $errorMessage = 'Simulated error';

    public function connect(string $host, int $port, DriverConfig $config): void
    {
        $this->connected = true;

        // Optional error simulation (parity with the Ruby recording client):
        //   "specific_driver_config": { "error_rate": 0.1, "error_message": "..." }
        $cfg = $config->specificDriverConfig;
        if (isset($cfg['error_rate'])) {
            $this->errorRate = max(0.0, min(1.0, (float) $cfg['error_rate']));
        }
        if (isset($cfg['error_message'])) {
            $this->errorMessage = (string) $cfg['error_message'];
        }
        if (isset($cfg['record_operations'])) {
            $this->recordOps = (bool) $cfg['record_operations'];
        }
    }

    private function record(string $op, string $key, int $size): void
    {
        if ($this->recordOps) {
            $this->recorded[] = ['op' => $op, 'key' => $key, 'size' => $size];
        }
    }

    private function shouldFail(): bool
    {
        if ($this->errorRate <= 0.0) {
            return false;
        }
        if ($this->errorRate >= 1.0) {
            return true;
        }

        return (mt_rand() / mt_getrandmax()) < $this->errorRate;
    }

    public function isConnected(): bool
    {
        return $this->connected;
    }

    public function ping(): TimedResult
    {
        return $this->measure(function (): string {
            $this->record('PING', '', 0);
            if ($this->shouldFail()) {
                throw new \RuntimeException($this->errorMessage);
            }

            return 'PONG';
        });
    }

    public function get(string $key): TimedResult
    {
        return $this->measure(function () use ($key): ?string {
            $this->record('GET', $key, 0);
            if ($this->shouldFail()) {
                throw new \RuntimeException($this->errorMessage);
            }

            return $this->store[$key] ?? null;
        });
    }

    public function set(string $key, string $value): TimedResult
    {
        return $this->measure(function () use ($key, $value): string {
            $this->record('SET', $key, strlen($value));
            if ($this->shouldFail()) {
                throw new \RuntimeException($this->errorMessage);
            }
            $this->store[$key] = $value;

            return 'OK';
        });
    }

    public function del(string $key): TimedResult
    {
        return $this->measure(function () use ($key): int {
            $this->record('DEL', $key, 0);
            if ($this->shouldFail()) {
                throw new \RuntimeException($this->errorMessage);
            }
            $existed = isset($this->store[$key]);
            unset($this->store[$key]);

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
