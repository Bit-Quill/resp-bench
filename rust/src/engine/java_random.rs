//! Faithful port of `java.util.Random` (48-bit LCG).
//!
//! Guarantees identical `uniform_rand` key sequences across the Java (reference),
//! Python, Go, Ruby, and C# engines. Reproduces `nextInt(bound)` exactly,
//! including the 32-bit signed-overflow rejection in the general case that avoids
//! modulo bias.
//!
//! See <https://docs.oracle.com/javase/8/docs/api/java/util/Random.html>.

const MULTIPLIER: i64 = 0x5DEECE66D;
const ADDEND: i64 = 0xB;
const MASK: i64 = (1 << 48) - 1;

/// A `java.util.Random`-compatible pseudo-random generator.
pub struct JavaRandom {
    seed: i64,
}

impl JavaRandom {
    pub fn new(seed: i64) -> Self {
        JavaRandom {
            seed: Self::initial_scramble(seed),
        }
    }

    pub fn set_seed(&mut self, seed: i64) {
        self.seed = Self::initial_scramble(seed);
    }

    fn initial_scramble(seed: i64) -> i64 {
        (seed ^ MULTIPLIER) & MASK
    }

    fn next_bits(&mut self, bits: u32) -> i32 {
        // The LCG step is computed modulo 2^48 (the mask). `wrapping_mul` keeps
        // the low 64 bits; masking to 48 then matches Java's arithmetic exactly.
        self.seed = self.seed.wrapping_mul(MULTIPLIER).wrapping_add(ADDEND) & MASK;
        // Java: (int)(seed >>> (48 - bits)). The shifted value fits in 32 bits.
        (self.seed >> (48 - bits)) as i32
    }

    /// Return a random int in `[0, bound)` matching Java's `nextInt(int)`.
    ///
    /// Panics if `bound <= 0`, mirroring Java's `IllegalArgumentException`.
    pub fn next_int(&mut self, bound: i32) -> i32 {
        assert!(bound > 0, "bound must be positive");

        // Power-of-two fast path (matches Java exactly).
        if (bound & -bound) == bound {
            return ((bound as i64 * self.next_bits(31) as i64) >> 31) as i32;
        }

        // General case: rejection sampling to avoid modulo bias. The rejection
        // condition relies on 32-bit signed overflow, which `i32` wrapping
        // arithmetic reproduces.
        loop {
            let bits = self.next_bits(31);
            let val = bits % bound;
            if bits.wrapping_sub(val).wrapping_add(bound - 1) >= 0 {
                return val;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The canonical seed-0 anchor shared across engines (bound 1000).
    #[test]
    fn seed_zero_anchor() {
        let mut r = JavaRandom::new(0);
        let seq: Vec<i32> = (0..10).map(|_| r.next_int(1000)).collect();
        assert_eq!(seq, vec![360, 948, 29, 447, 515, 53, 491, 761, 719, 854]);
    }

    #[test]
    fn same_seed_same_sequence() {
        let mut a = JavaRandom::new(12345);
        let mut b = JavaRandom::new(12345);
        for _ in 0..100 {
            assert_eq!(a.next_int(1000), b.next_int(1000));
        }
    }

    #[test]
    fn power_of_two_bound() {
        // Should not panic and must stay within range.
        let mut r = JavaRandom::new(42);
        for _ in 0..1000 {
            let v = r.next_int(1024);
            assert!((0..1024).contains(&v));
        }
    }
}
