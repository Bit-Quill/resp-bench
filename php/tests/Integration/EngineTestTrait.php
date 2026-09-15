<?php

declare(strict_types=1);

namespace RespBench\Tests\Integration;

use RespBench\Config\DriverConfig;
use RespBench\Config\Loader;
use RespBench\Config\WorkloadConfig;
use RespBench\Engine\Benchmark;

/**
 * Shared helpers for black-box integration tests that run the engine end-to-end
 * with the recording driver (server-free) and validate the NDJSON output.
 *
 * The recording driver runs in inline (single-process) mode, so wall-clock and
 * duration_ms reflect the engine's own timing without fork overhead — which is
 * what makes the rate-limiting timing assertions reliable.
 */
trait EngineTestTrait
{
    private function recordingDriver(): DriverConfig
    {
        return new DriverConfig(driverId: 'recording', mode: 'standalone');
    }

    private function parseWorkload(string $json): WorkloadConfig
    {
        return Loader::parseWorkloadConfigString($json);
    }

    /**
     * Run the engine with the recording driver (forced inline) and return the
     * parsed NDJSON phase objects.
     *
     * @return list<array<string,mixed>>
     */
    private function runEngine(WorkloadConfig $workload, ?string &$metricsPath = null): array
    {
        $metricsPath = sys_get_temp_dir() . '/resp_bench_php_it_' . uniqid('', true) . '.ndjson';

        $engine = new Benchmark(
            host: 'localhost',
            port: 6379,
            driverConfig: $this->recordingDriver(),
            workloadConfig: $workload,
            metricsPath: $metricsPath,
            commitId: 'it-test',
            concurrencyMode: 'inline',
        );
        $engine->run();

        $lines = array_values(array_filter(explode("\n", (string) file_get_contents($metricsPath))));
        unlink($metricsPath);

        return array_map(
            static fn (string $l): array => json_decode($l, true, 512, JSON_THROW_ON_ERROR),
            $lines,
        );
    }
}
