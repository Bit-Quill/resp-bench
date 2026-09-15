<?php

declare(strict_types=1);

namespace RespBench\Config;

/**
 * Configuration for key generation in a benchmark phase.
 *
 * Mirrors the Ruby/Java KeyspaceConfig defaults so key sequences match
 * across engines.
 */
final class KeyspaceConfig
{
    public const DEFAULT_KEY_SIZE_BYTES = 16;
    public const DEFAULT_KEY_PREFIX = 'bench:';

    public function __construct(
        public readonly int $keysCount,
        public readonly int $keySizeBytes = self::DEFAULT_KEY_SIZE_BYTES,
        public readonly string $keyPrefix = self::DEFAULT_KEY_PREFIX,
        public readonly string $generationAlg = 'sequential_int',
        public readonly ?int $seed = null,
    ) {
    }

    public function isSequentialInt(): bool
    {
        return $this->generationAlg === 'sequential_int';
    }

    public function isUniformRand(): bool
    {
        return $this->generationAlg === 'uniform_rand';
    }

    public function effectiveKeyPrefix(): string
    {
        return $this->keyPrefix !== '' ? $this->keyPrefix : self::DEFAULT_KEY_PREFIX;
    }

    /**
     * Returns the seed value (defaults to 0 if not set), matching Ruby's seed_value.
     */
    public function seedValue(): int
    {
        return $this->seed ?? 0;
    }
}
