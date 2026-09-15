<?php

declare(strict_types=1);

namespace RespBench\Engine;

use RespBench\Config\KeyspaceConfig;

/**
 * Key generator for benchmark operations.
 *
 * Produces identical key sequences to the Java reference implementation (and the
 * Ruby/Python/Node engines) for the same seed.
 *
 * Two algorithms:
 *  - sequential_int: keys 0, 1, 2, ... N-1 (wraps around)
 *  - uniform_rand:   random keys using the Java-compatible LCG (JavaRandom)
 *
 * NOTE ON CONCURRENCY: In the PHP engine each connection runs in its own forked
 * process, so a generator instance is never shared across processes. The
 * sequential counter is therefore a plain integer (no atomics needed within a
 * process). For cross-process parity of `sequential_int`, workers are assigned
 * disjoint, deterministic starting offsets by the engine (see Benchmark).
 */
final class KeyGenerator
{
    private readonly string $keyPrefix;
    private readonly int $keySizeBytes;
    private readonly int $keysCount;
    private readonly int $seed;
    private readonly bool $sequential;

    private int $sequentialCounter = 0;
    private JavaRandom $random;

    public function __construct(
        private readonly KeyspaceConfig $config,
        ?int $seedOverride = null,
    ) {
        $this->keyPrefix = $config->effectiveKeyPrefix();
        $this->keySizeBytes = $config->keySizeBytes;
        $this->keysCount = $config->keysCount;
        $this->seed = $seedOverride ?? $config->seedValue();
        $this->sequential = $config->isSequentialInt();
        $this->random = new JavaRandom($this->seed);
    }

    public static function create(KeyspaceConfig $config): self
    {
        return new self($config);
    }

    public static function createWithSeed(KeyspaceConfig $config, int $seed): self
    {
        return new self($config, $seed);
    }

    /**
     * Generate the next key.
     */
    public function nextKey(): string
    {
        if ($this->sequential) {
            $keyIndex = $this->sequentialCounter;
            $this->sequentialCounter++;
        } else {
            $keyIndex = $this->random->nextInt($this->keysCount);
        }

        $keyIndex %= $this->keysCount;

        return $this->formatKey($keyIndex);
    }

    /**
     * Reset the generator to its initial state.
     */
    public function reset(): void
    {
        $this->sequentialCounter = 0;
        $this->random->setSeed($this->seed);
    }

    /**
     * Format a key index into a full key string.
     * Matches Java's String.format("%0Nd", keyIndex): zero-padded so that
     * prefix + number is approximately key_size_bytes wide (minimum 1 digit).
     */
    private function formatKey(int $keyIndex): string
    {
        $paddingWidth = max($this->keySizeBytes - strlen($this->keyPrefix), 1);

        return $this->keyPrefix . sprintf('%0' . $paddingWidth . 'd', $keyIndex);
    }
}
