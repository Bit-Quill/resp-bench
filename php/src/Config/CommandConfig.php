<?php

declare(strict_types=1);

namespace RespBench\Config;

/**
 * Configuration for a command in a benchmark phase.
 */
final class CommandConfig
{
    public const DEFAULT_DATA_SIZE_BYTES = 256;

    public readonly string $command;
    public readonly float $weight;
    public readonly int $dataSizeBytes;

    public function __construct(
        string $command,
        float $weight,
        ?int $dataSizeBytes = self::DEFAULT_DATA_SIZE_BYTES,
    ) {
        $this->command = strtolower($command);
        $this->weight = $weight;
        $this->dataSizeBytes = $dataSizeBytes ?? self::DEFAULT_DATA_SIZE_BYTES;
    }
}
