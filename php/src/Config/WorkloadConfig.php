<?php

declare(strict_types=1);

namespace RespBench\Config;

/**
 * Configuration for a benchmark workload.
 * Maps to configs/schemas/workload-config.schema.json.
 */
final class WorkloadConfig
{
    /**
     * @param array<string,mixed> $benchmarkProfile
     * @param list<PhaseConfig> $phases
     */
    public function __construct(
        public readonly string $schemaVersion,
        public readonly array $benchmarkProfile,
        public readonly array $phases,
    ) {
    }

    public function name(): ?string
    {
        $value = $this->benchmarkProfile['name'] ?? null;

        return is_string($value) ? $value : null;
    }

    public function description(): ?string
    {
        $value = $this->benchmarkProfile['description'] ?? null;

        return is_string($value) ? $value : null;
    }
}
