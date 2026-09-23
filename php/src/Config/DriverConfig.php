<?php

declare(strict_types=1);

namespace RespBench\Config;

/**
 * Configuration for a benchmark driver (client library).
 * Maps to configs/schemas/driver-config.schema.json.
 */
final class DriverConfig
{
    /**
     * @param array<string,mixed> $specificDriverConfig
     * @param array<string,mixed>|null $tls
     * @param array<string,mixed>|null $auth
     */
    public function __construct(
        public readonly string $schemaVersion = '1.0',
        public readonly ?string $description = null,
        public readonly ?string $driverId = null,
        public readonly string $mode = 'standalone',
        public readonly ?array $tls = null,
        public readonly ?array $auth = null,
        public readonly array $specificDriverConfig = [],
    ) {
    }

    public function secondaryDriverId(): ?string
    {
        $value = $this->specificDriverConfig['secondary_driver_id'] ?? null;

        return is_string($value) ? $value : null;
    }

    public function isStandalone(): bool
    {
        return $this->mode === 'standalone';
    }

    public function isCluster(): bool
    {
        return $this->mode === 'cluster';
    }

    public function isSentinel(): bool
    {
        return $this->mode === 'sentinel';
    }
}
