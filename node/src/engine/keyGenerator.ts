/**
 * Key generator producing sequences identical to the other engines.
 *
 * - `sequential_int`: keys 0, 1, 2, ... N-1, wrapping around.
 * - `uniform_rand`: Java-LCG random keys (see ./javaRandom.ts).
 *
 * Key formatting matches Java's `String.format("%0Nd", index)`: the numeric part
 * is zero-padded to `max(keySizeBytes - prefix.length, 1)` digits. With the
 * reference configs (`key_prefix: "bench:"`, `key_size_bytes: 16`) that yields
 * `bench:` + 10 digits, e.g. `bench:0000000042`.
 *
 * Cross-worker semantics follow the Java reference:
 * - `sequential_int` uses a counter SHARED across all workers in a phase, so the
 *   workers collectively emit 0, 1, 2, ... (this is what populates the whole
 *   keyspace during a WARMUP/populate phase). Pass a shared Counter.
 * - `uniform_rand` uses a per-worker RNG seeded `baseSeed + workerIndex`.
 */

import type { KeyspaceConfig } from '../config/keyspaceConfig.js';
import { JavaRandom } from './javaRandom.js';

/**
 * A monotonic 0-based counter, shared across a phase's workers.
 *
 * Safe to share across concurrent workers: `nextValue` performs its
 * read-increment with no `await` in between, so it is atomic on the single
 * JS event-loop thread.
 */
export class Counter {
  private value: number;

  constructor(start = 0) {
    this.value = start;
  }

  nextValue(): number {
    return this.value++;
  }

  reset(): void {
    this.value = 0;
  }
}

export class KeyGenerator {
  private readonly config: KeyspaceConfig;
  private readonly keyPrefix: string;
  private readonly keysCount: number;
  private readonly paddingWidth: number;
  private readonly seed: number;
  private readonly sequentialCounter: Counter;
  private readonly random: JavaRandom;

  constructor(config: KeyspaceConfig, seedOverride?: number, sequentialCounter?: Counter) {
    this.config = config;
    this.keyPrefix = config.keyPrefix;
    this.keysCount = config.keysCount;
    this.paddingWidth = Math.max(config.keySizeBytes - config.keyPrefix.length, 1);
    this.seed = seedOverride ?? config.seedValue();
    this.sequentialCounter = sequentialCounter ?? new Counter();
    this.random = new JavaRandom(this.seed);
  }

  static create(config: KeyspaceConfig): KeyGenerator {
    return new KeyGenerator(config);
  }

  /** Per-worker generator with a unique seed and an optional shared counter. */
  static createWithSeed(
    config: KeyspaceConfig,
    seed: number,
    sequentialCounter?: Counter,
  ): KeyGenerator {
    return new KeyGenerator(config, seed, sequentialCounter);
  }

  nextKey(): string {
    const rawIndex = this.config.isSequentialInt()
      ? this.sequentialCounter.nextValue()
      : this.random.nextInt(this.keysCount);

    return this.formatKey(rawIndex % this.keysCount);
  }

  reset(): void {
    this.sequentialCounter.reset();
    this.random.setSeed(this.seed);
  }

  private formatKey(keyIndex: number): string {
    return this.keyPrefix + String(keyIndex).padStart(this.paddingWidth, '0');
  }
}
