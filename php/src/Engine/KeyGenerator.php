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
 * CROSS-PROCESS PARTITIONING: In the PHP engine each connection runs in its own
 * forked process, so a generator instance is never shared across processes and a
 * naive per-worker counter starting at 0 would make every worker write the SAME
 * keys — populating only `keys_count / N` distinct keys. To reproduce Java's key
 * set (whose forked threads share one counter and together walk 0..total-1), a
 * `sequential_int` worker strides its own disjoint subset: worker `i` of `N`
 * emits indices i, i+N, i+2N, … The union over all workers is exactly the full
 * keyspace with no duplicates (the interleaving order differs from Java's, which
 * does not matter for which keys end up populated).
 */
final class KeyGenerator
{
    private readonly string $keyPrefix;
    private readonly int $keySizeBytes;
    private readonly int $keysCount;
    private readonly int $seed;
    private readonly bool $sequential;
    private readonly int $workerIndex;
    private readonly int $workerCount;

    private int $sequentialStep = 0;
    private JavaRandom $random;

    public function __construct(
        private readonly KeyspaceConfig $config,
        ?int $seedOverride = null,
        int $workerIndex = 0,
        int $workerCount = 1,
    ) {
        $this->keyPrefix = $config->effectiveKeyPrefix();
        $this->keySizeBytes = $config->keySizeBytes;
        $this->keysCount = $config->keysCount;
        $this->seed = $seedOverride ?? $config->seedValue();
        $this->sequential = $config->isSequentialInt();
        $this->workerIndex = max(0, $workerIndex);
        $this->workerCount = max(1, $workerCount);
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
     * Create a generator for worker `workerIndex` of `workerCount`.
     *
     * - sequential_int: strides a disjoint subset (i, i+N, i+2N, …).
     * - uniform_rand:   seeds per worker (seed + workerIndex) for reproducible,
     *   distinct random streams.
     */
    public static function forWorker(KeyspaceConfig $config, int $workerIndex, int $workerCount): self
    {
        $seedOverride = $config->isUniformRand()
            ? $config->seedValue() + $workerIndex
            : null;

        return new self($config, $seedOverride, $workerIndex, $workerCount);
    }

    /**
     * Generate the next key.
     */
    public function nextKey(): string
    {
        if ($this->sequential) {
            // Stride: worker i emits i, i+N, i+2N, … (mod keysCount).
            $keyIndex = ($this->workerIndex + $this->sequentialStep * $this->workerCount) % $this->keysCount;
            $this->sequentialStep++;
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
        $this->sequentialStep = 0;
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
