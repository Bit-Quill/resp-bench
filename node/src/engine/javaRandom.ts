/**
 * Faithful port of `java.util.Random` (48-bit LCG).
 *
 * This guarantees identical `uniform_rand` key sequences across the Java
 * (reference), Ruby, C#, Python and Node engines. Java is the canonical source,
 * so this port reproduces `nextInt(bound)` exactly -- including the 32-bit
 * signed-overflow rejection in the general case, which is what avoids modulo
 * bias. (The Ruby port omits that rejection because Ruby integers are
 * arbitrary-precision; this port emulates the int32 wraparound so it matches
 * the Java reference rather than the Ruby approximation.)
 *
 * BigInt is mandatory for the state, not a stylistic choice: `seed * MULTIPLIER`
 * reaches ~2^83, far past the 2^53 that a JS `number` can represent exactly, so
 * a `number`-based port silently diverges from Java after the first step.
 *
 * @see https://docs.oracle.com/javase/8/docs/api/java/util/Random.html
 */

const MULTIPLIER = 0x5deece66dn;
const ADDEND = 0xbn;
const MASK = (1n << 48n) - 1n;

/** Interpret the low 32 bits of `value` as a signed 32-bit integer. */
export function toInt32(value: number): number {
  return value | 0;
}

export class JavaRandom {
  private seed: bigint;

  constructor(seed: number | bigint) {
    this.seed = JavaRandom.initialScramble(seed);
  }

  setSeed(seed: number | bigint): void {
    this.seed = JavaRandom.initialScramble(seed);
  }

  /** Return a random int in [0, bound) matching Java's nextInt(int). */
  nextInt(bound: number): number {
    if (!Number.isInteger(bound) || bound <= 0) {
      throw new Error(`bound must be a positive integer (got ${bound})`);
    }

    // Power-of-two fast path (matches Java exactly).
    if ((bound & -bound) === bound) {
      return Number((BigInt(bound) * BigInt(this.next(31))) >> 31n);
    }

    // General case: rejection sampling to avoid modulo bias. The rejection
    // condition relies on 32-bit signed overflow, which `| 0` emulates.
    for (;;) {
      const bits = this.next(31);
      const val = bits % bound;
      if (toInt32(bits - val + (bound - 1)) >= 0) return val;
    }
  }

  private static initialScramble(seed: number | bigint): bigint {
    return (BigInt(seed) ^ MULTIPLIER) & MASK;
  }

  /** Java's `protected int next(int bits)`. Exposed for parity tests. */
  next(bits: number): number {
    this.seed = (this.seed * MULTIPLIER + ADDEND) & MASK;
    return Number(this.seed >> BigInt(48 - bits));
  }
}
