<?php

declare(strict_types=1);

namespace RespBench\Metrics;

use RuntimeException;

/**
 * Writes benchmark metrics to NDJSON (newline-delimited JSON), one line per phase.
 * The schema matches the Java/Ruby/Python engines exactly.
 */
final class NdjsonWriter
{
    private ?string $commitId = null;
    private ?string $driverId = null;
    private ?string $primaryDriverVersion = null;
    private ?string $secondaryDriverId = null;
    private ?string $secondaryDriverVersion = null;

    public function __construct(private readonly string $outputPath)
    {
    }

    public function setMetadata(
        ?string $commitId,
        ?string $driverId,
        ?string $primaryDriverVersion,
        ?string $secondaryDriverId = null,
        ?string $secondaryDriverVersion = null,
    ): void {
        $this->commitId = $commitId;
        $this->driverId = $driverId;
        $this->primaryDriverVersion = $primaryDriverVersion;
        $this->secondaryDriverId = $secondaryDriverId;
        $this->secondaryDriverVersion = $secondaryDriverVersion;
    }

    public function writePhaseResults(string $phaseId, string $status, int $connections, Collector $collector): void
    {
        $dir = dirname($this->outputPath);
        if (!is_dir($dir) && !mkdir($dir, 0o777, true) && !is_dir($dir)) {
            throw new RuntimeException("Cannot create output directory: {$dir}");
        }

        $json = $this->buildPhaseJson($phaseId, $status, $connections, $collector);
        $line = json_encode($json, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);

        file_put_contents($this->outputPath, $line . "\n", FILE_APPEND | LOCK_EX);
    }

    /**
     * @return array<string,mixed>
     */
    private function buildPhaseJson(string $phaseId, string $status, int $connections, Collector $collector): array
    {
        $result = [];

        if ($this->commitId !== null || $this->driverId !== null) {
            $metadata = [];
            if ($this->commitId !== null) {
                $metadata['commit_id'] = $this->commitId;
            }
            $metadata['timestamp'] = gmdate('Y-m-d\TH:i:s\Z');
            if ($this->driverId !== null) {
                $metadata['driver_id'] = $this->driverId;
            }
            if ($this->primaryDriverVersion !== null) {
                $metadata['primary_driver_version'] = $this->primaryDriverVersion;
            }
            if ($this->secondaryDriverId !== null) {
                $metadata['secondary_driver_id'] = $this->secondaryDriverId;
            }
            if ($this->secondaryDriverVersion !== null) {
                $metadata['secondary_driver_version'] = $this->secondaryDriverVersion;
            }
            $result['metadata'] = $metadata;
        }

        $result['phase'] = [
            'id' => $phaseId,
            'status' => $status,
            'start_timestamp' => self::iso8601($collector->startTime()),
            'finish_timestamp' => self::iso8601($collector->endTime()),
            'duration_ms' => $collector->durationMillis(),
            'connections' => $connections,
        ];

        $result['totals'] = [
            'requests' => $collector->totalRequests(),
            'errors' => $collector->totalErrors(),
        ];

        $result['metrics'] = $this->buildCommandMetrics($collector);

        return $result;
    }

    /**
     * @return array<string,mixed>
     */
    private function buildCommandMetrics(Collector $collector): array
    {
        $metrics = [];

        foreach ($collector->allMetrics() as $name => $cmd) {
            $data = [
                'requests' => $cmd->requests(),
                'errors' => $cmd->errors(),
                'latency' => [
                    'unit' => 'us',
                    'count' => $cmd->count(),
                    'summary' => [
                        'min' => $cmd->min(),
                        'p50' => $cmd->percentile(50),
                        'p95' => $cmd->percentile(95),
                        'p99' => $cmd->percentile(99),
                        'p999' => $cmd->percentile(99.9),
                        'max' => $cmd->max(),
                    ],
                ],
            ];

            $histogram = $cmd->histogram();
            if ($histogram !== null) {
                $data['latency']['hdr'] = [
                    'format' => 'hdr',
                    'sigfig' => 3,
                    'payload_b64' => self::encodeHistogram($histogram),
                ];
            }

            $metrics[$name] = $data;
        }

        return $metrics;
    }

    private static function encodeHistogram(HdrHistogram $histogram): string
    {
        try {
            return HdrEncoder::encodeCompressedBase64($histogram);
        } catch (\Throwable) {
            return '';
        }
    }

    private static function iso8601(?float $time): ?string
    {
        if ($time === null) {
            return null;
        }

        return gmdate('Y-m-d\TH:i:s\Z', (int) $time);
    }
}
