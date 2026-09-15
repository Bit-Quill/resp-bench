/** Key-generation configuration for a benchmark phase. */

export const DEFAULT_KEY_SIZE_BYTES = 16;
export const DEFAULT_KEY_PREFIX = 'bench:';
export const DEFAULT_GENERATION_ALG = 'sequential_int';

export class KeyspaceConfig {
  readonly keysCount: number;
  readonly keySizeBytes: number;
  readonly keyPrefix: string;
  readonly generationAlg: string;
  readonly seed: number | null;

  constructor(init: {
    keysCount: number;
    keySizeBytes?: number | null;
    keyPrefix?: string | null;
    generationAlg?: string | null;
    seed?: number | null;
  }) {
    this.keysCount = init.keysCount;
    // A null/undefined value falls back to the default rather than staying
    // null, matching the Ruby/Python engines.
    this.keySizeBytes = init.keySizeBytes ?? DEFAULT_KEY_SIZE_BYTES;
    this.keyPrefix = init.keyPrefix ?? DEFAULT_KEY_PREFIX;
    this.generationAlg = init.generationAlg ?? DEFAULT_GENERATION_ALG;
    this.seed = init.seed ?? null;
  }

  isSequentialInt(): boolean {
    return this.generationAlg === 'sequential_int';
  }

  isUniformRand(): boolean {
    return this.generationAlg === 'uniform_rand';
  }

  seedValue(): number {
    return this.seed ?? 0;
  }
}
