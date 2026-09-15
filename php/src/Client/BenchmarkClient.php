<?php

declare(strict_types=1);

namespace RespBench\Client;

use RespBench\Config\DriverConfig;
use Throwable;

/**
 * Abstract base class for benchmark clients. All driver implementations extend this.
 */
abstract class BenchmarkClient
{
    abstract public function connect(string $host, int $port, DriverConfig $config): void;

    abstract public function isConnected(): bool;

    abstract public function ping(): TimedResult;

    abstract public function get(string $key): TimedResult;

    abstract public function set(string $key, string $value): TimedResult;

    abstract public function del(string $key): TimedResult;

    abstract public function close(): void;

    abstract public function driverVersion(): string;

    public function secondaryDriverVersion(): ?string
    {
        return null;
    }

    /**
     * Measure the execution time of a callable in microseconds, capturing errors.
     *
     * @param callable():mixed $operation
     */
    protected function measure(callable $operation): TimedResult
    {
        $start = hrtime(true);
        try {
            $value = $operation();
            $latency = intdiv(hrtime(true) - $start, 1000);

            return new TimedResult($value, $latency);
        } catch (Throwable $e) {
            $latency = intdiv(hrtime(true) - $start, 1000);

            return new TimedResult(null, $latency, $e);
        }
    }
}
