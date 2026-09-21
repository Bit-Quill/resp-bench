<?php

declare(strict_types=1);

namespace RespBench\Engine;

use InvalidArgumentException;

/**
 * PHP port of Java's java.util.Random 48-bit Linear Congruential Generator.
 *
 * Produces identical random sequences to the Java (reference), Ruby, Python, and
 * Node engines for the same seed, guaranteeing cross-language key-sequence parity.
 *
 * Java's Random uses:
 *   - multiplier: 0x5DEECE66D (25214903917)
 *   - addend:     0xB (11)
 *   - mask:       (1 << 48) - 1
 *
 * PHP integers are 64-bit signed on all supported (64-bit) platforms, which is
 * sufficient to hold the 48-bit state without overflow.
 *
 * @see https://docs.oracle.com/javase/8/docs/api/java/util/Random.html
 */
final class JavaRandom
{
    private const MULTIPLIER = 0x5DEECE66D;
    private const ADDEND = 0xB;
    private const MASK = (1 << 48) - 1;

    private int $seed;

    public function __construct(int $seed)
    {
        $this->seed = $this->initialScramble($seed);
    }

    /**
     * Generate the next random integer in range [0, bound).
     * Matches Java's Random.nextInt(int bound).
     */
    public function nextInt(int $bound): int
    {
        if ($bound <= 0) {
            throw new InvalidArgumentException('bound must be positive');
        }

        // Special case for powers of two.
        if (($bound & -$bound) === $bound) {
            return (int) (($bound * $this->nextBits(31)) >> 31);
        }

        // General case - rejection sampling to avoid modulo bias.
        //
        // Java relies on signed 32-bit overflow: it rejects when
        //   bits - val + (bound - 1) < 0
        // i.e. when the int32 sum overflows past Integer.MAX_VALUE. PHP ints are
        // 64-bit, so that sum is never negative and the branch would never fire —
        // leaving the two LCG streams permanently out of step. Emulate the
        // wraparound by accepting only while the sum stays within int32 range
        // (matches the Node port's toInt32 approach).
        while (true) {
            $bits = $this->nextBits(31);
            $val = $bits % $bound;
            if ($bits - $val + ($bound - 1) <= 2147483647) {
                return $val;
            }
        }
    }

    /**
     * Reset the generator with a new seed.
     */
    public function setSeed(int $seed): void
    {
        $this->seed = $this->initialScramble($seed);
    }

    private function initialScramble(int $seed): int
    {
        return ($seed ^ self::MULTIPLIER) & self::MASK;
    }

    /**
     * Generate the next `bits` random bits (1-32).
     *
     * Java's LCG relies on 64-bit integer overflow: it computes the full
     * product `seed * MULTIPLIER` and keeps the low 48 bits. In PHP a direct
     * multiply overflows the 64-bit signed int and silently becomes a float,
     * corrupting the low bits. We therefore compute `(seed * MULTIPLIER) mod 2^48`
     * using a 24-bit split so every partial product stays well under 2^63.
     *
     *   seed = hi * 2^24 + lo         (hi, lo < 2^24)
     *   seed * M  ≡  (lo*M) + ((hi*M mod 2^24) << 24)   (mod 2^48)
     *
     * With M = 0x5DEECE66D (< 2^35), both lo*M and hi*M are < 2^59 — no overflow.
     */
    private function nextBits(int $bits): int
    {
        $seed = $this->seed;
        $lo = $seed & 0xFFFFFF;          // low 24 bits
        $hi = ($seed >> 24) & 0xFFFFFF;  // next 24 bits

        $product = (($lo * self::MULTIPLIER)
            + ((($hi * self::MULTIPLIER) & 0xFFFFFF) << 24)) & self::MASK;

        $this->seed = ($product + self::ADDEND) & self::MASK;

        return $this->seed >> (48 - $bits);
    }
}
