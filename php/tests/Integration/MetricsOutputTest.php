<?php

declare(strict_types=1);

namespace RespBench\Tests\Integration;

use PHPUnit\Framework\TestCase;
use RespBench\Metrics\HdrEncoder;
use RespBench\Metrics\HdrHistogram;

/**
 * Validates the NDJSON metrics output format end-to-end (mirrors the Java/Ruby
 * MetricsOutputTest): metadata, phase metadata, request counts, per-command
 * metrics, and the HDR histogram block. Latency-distribution accuracy is
 * validated directly against the HdrHistogram (the recording driver has ~0
 * latency, so realistic percentiles are asserted at the histogram level).
 */
final class MetricsOutputTest extends TestCase
{
    use EngineTestTrait;

    public function testNdjsonMetadataAndPhaseStructure(): void
    {
        $workload = $this->parseWorkload($this->twoPhaseWorkload());
        $phases = $this->runEngine($workload);

        self::assertCount(2, $phases);

        foreach ($phases as $p) {
            // Metadata block.
            self::assertArrayHasKey('metadata', $p);
            self::assertSame('it-test', $p['metadata']['commit_id']);
            self::assertSame('recording', $p['metadata']['driver_id']);
            self::assertArrayHasKey('timestamp', $p['metadata']);
            self::assertMatchesRegularExpression(
                '/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/',
                $p['metadata']['timestamp'],
            );

            // Phase block.
            self::assertArrayHasKey('phase', $p);
            self::assertContains($p['phase']['id'], ['WARMUP', 'STEADY']);
            self::assertSame('COMPLETED', $p['phase']['status']);
            self::assertArrayHasKey('duration_ms', $p['phase']);
            self::assertArrayHasKey('connections', $p['phase']);

            // Totals block.
            self::assertArrayHasKey('totals', $p);
            self::assertArrayHasKey('requests', $p['totals']);
            self::assertArrayHasKey('errors', $p['totals']);
        }
    }

    public function testRequestCountsAreExact(): void
    {
        $workload = $this->parseWorkload($this->twoPhaseWorkload());
        $phases = $this->runEngine($workload);

        // WARMUP: 400 SET requests.
        self::assertSame(400, $phases[0]['totals']['requests']);
        self::assertSame(400, $phases[0]['metrics']['SET']['requests']);

        // STEADY: 1000 total; GET + SET requests sum to total.
        self::assertSame(1000, $phases[1]['totals']['requests']);
        $sum = $phases[1]['metrics']['GET']['requests'] + $phases[1]['metrics']['SET']['requests'];
        self::assertSame(1000, $sum);

        // Per-command latency count equals its (successful) request count.
        self::assertSame(
            $phases[1]['metrics']['GET']['requests'],
            $phases[1]['metrics']['GET']['latency']['count'],
        );
    }

    public function testHdrBlockShapeAndUnit(): void
    {
        $workload = $this->parseWorkload($this->twoPhaseWorkload());
        $phases = $this->runEngine($workload);

        $latency = $phases[1]['metrics']['GET']['latency'];
        self::assertSame('us', $latency['unit']);

        foreach (['min', 'p50', 'p95', 'p99', 'p999', 'max'] as $k) {
            self::assertArrayHasKey($k, $latency['summary']);
            self::assertIsInt($latency['summary'][$k]);
        }

        $hdr = $latency['hdr'];
        self::assertSame('hdr', $hdr['format']);
        self::assertSame(3, $hdr['sigfig']);
        self::assertNotSame('', $hdr['payload_b64']);

        // Payload is a valid base64 V2 compressed HDR blob.
        $raw = base64_decode($hdr['payload_b64'], true);
        self::assertIsString($raw);
        /** @var array{cookie:int} $wrapper */
        $wrapper = unpack('Ncookie', substr($raw, 0, 4));
        self::assertSame(0x1c849314, $wrapper['cookie']);
    }

    /**
     * Latency-distribution accuracy: with a known latency mix, the HDR summary
     * percentiles must land in the right buckets. This exercises the same
     * histogram + encoder used by the NDJSON writer.
     */
    public function testLatencyPercentileAccuracy(): void
    {
        $h = new HdrHistogram(1, 600_000_000, 3);
        // 95 samples at ~100us, 4 at ~1000us, 1 at ~10000us.
        for ($i = 0; $i < 95; $i++) {
            $h->record(100);
        }
        for ($i = 0; $i < 4; $i++) {
            $h->record(1000);
        }
        $h->record(10000);

        self::assertEqualsWithDelta(100, $h->valueAtPercentile(50), 1);
        self::assertEqualsWithDelta(100, $h->valueAtPercentile(90), 1);
        self::assertEqualsWithDelta(1000, $h->valueAtPercentile(99), 10);
        self::assertEqualsWithDelta(10000, $h->valueAtPercentile(100), 50);

        // Round-trips through the encoder without error.
        $b64 = HdrEncoder::encodeCompressedBase64($h);
        self::assertNotSame('', $b64);
    }

    private function twoPhaseWorkload(): string
    {
        return <<<JSON
        {
            "benchmark_profile": {"name": "MetricsOutputTest"},
            "phases": [
                {
                    "id": "WARMUP",
                    "connections": 4,
                    "completion": {"type": "requests", "requests": 400},
                    "keyspace": {"keys_count": 1000, "key_size_bytes": 16, "key_prefix": "bench:", "generation_alg": "sequential_int"},
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 64}]
                },
                {
                    "id": "STEADY",
                    "connections": 4,
                    "completion": {"type": "requests", "requests": 1000},
                    "keyspace": {"keys_count": 1000, "key_size_bytes": 16, "key_prefix": "bench:", "generation_alg": "uniform_rand", "seed": 12345},
                    "commands": [
                        {"command": "get", "weight": 0.8},
                        {"command": "set", "weight": 0.2, "data_size_bytes": 64}
                    ]
                }
            ]
        }
        JSON;
    }
}
