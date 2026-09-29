//! Key generator producing sequences identical to the other engines.
//!
//! - `sequential_int`: keys 0, 1, 2, ... N-1, wrapping around, from a counter
//!   SHARED across a phase's workers (so they collectively emit 0, 1, 2, ...).
//! - `uniform_rand`: Java-LCG random keys from a per-worker RNG seeded
//!   `base_seed + worker_index`.
//!
//! Key formatting matches Java's `String.format("%0Nd", index)`: the numeric part
//! is zero-padded to `max(key_size_bytes - prefix.len(), 1)` digits.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use crate::config::KeyspaceConfig;

use super::java_random::JavaRandom;

/// A monotonic 0-based counter shared across a phase's workers.
#[derive(Debug, Default)]
pub struct SharedCounter {
    value: AtomicU64,
}

impl SharedCounter {
    pub fn new() -> Arc<Self> {
        Arc::new(SharedCounter {
            value: AtomicU64::new(0),
        })
    }

    /// Return the current value and increment (atomic across threads).
    pub fn next_value(&self) -> u64 {
        self.value.fetch_add(1, Ordering::Relaxed)
    }
}

/// Generates keys for one worker/connection.
pub struct KeyGenerator {
    key_prefix: String,
    padding_width: usize,
    keys_count: u64,
    is_sequential: bool,
    counter: Arc<SharedCounter>,
    random: JavaRandom,
}

impl KeyGenerator {
    /// Per-worker generator with a unique seed and a shared sequential counter.
    pub fn with_seed(config: &KeyspaceConfig, seed: i64, counter: Arc<SharedCounter>) -> Self {
        let key_prefix = config.key_prefix.clone();
        let padding_width = config
            .key_size_bytes
            .saturating_sub(key_prefix.len())
            .max(1);
        KeyGenerator {
            key_prefix,
            padding_width,
            keys_count: config.keys_count,
            is_sequential: config.is_sequential_int(),
            counter,
            random: JavaRandom::new(seed),
        }
    }

    /// Convenience constructor for tests: single worker, fresh counter.
    #[cfg(test)]
    pub fn create(config: &KeyspaceConfig) -> Self {
        Self::with_seed(config, config.seed_value(), SharedCounter::new())
    }

    pub fn next_key(&mut self) -> String {
        let key_index = if self.is_sequential {
            self.counter.next_value() % self.keys_count
        } else {
            // keys_count fits in i32 range for uniform_rand keyspaces (bounded by
            // the JavaRandom API); the modulo keeps parity with peers regardless.
            let raw = self.random.next_int(self.keys_count as i32) as u64;
            raw % self.keys_count
        };
        format!(
            "{}{:0width$}",
            self.key_prefix,
            key_index,
            width = self.padding_width
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg(keys: u64, prefix: &str, alg: &str, seed: Option<i64>) -> KeyspaceConfig {
        KeyspaceConfig {
            keys_count: keys,
            key_size_bytes: 16,
            key_prefix: prefix.to_string(),
            generation_alg: alg.to_string(),
            seed,
        }
    }

    #[test]
    fn sequential_wraps() {
        let c = cfg(3, "test:", "sequential_int", None);
        let mut g = KeyGenerator::create(&c);
        // padding = max(16 - len("test:")=5, 1) = 11
        assert_eq!(g.next_key(), "test:00000000000");
        assert_eq!(g.next_key(), "test:00000000001");
        assert_eq!(g.next_key(), "test:00000000002");
        assert_eq!(g.next_key(), "test:00000000000");
    }

    #[test]
    fn sequential_shared_counter_partitions() {
        let c = cfg(1000, "k:", "sequential_int", None);
        let counter = SharedCounter::new();
        let mut a = KeyGenerator::with_seed(&c, 0, counter.clone());
        let mut b = KeyGenerator::with_seed(&c, 1, counter.clone());
        // padding = max(16 - len("k:")=2, 1) = 14. Two workers sharing the
        // counter emit distinct, increasing indices.
        assert_eq!(a.next_key(), "k:00000000000000");
        assert_eq!(b.next_key(), "k:00000000000001");
        assert_eq!(a.next_key(), "k:00000000000002");
    }

    #[test]
    fn uniform_rand_reproducible() {
        let c = cfg(1000, "t:", "uniform_rand", Some(12345));
        let mut a = KeyGenerator::with_seed(&c, 12345, SharedCounter::new());
        let mut b = KeyGenerator::with_seed(&c, 12345, SharedCounter::new());
        for _ in 0..100 {
            assert_eq!(a.next_key(), b.next_key());
        }
    }

    #[test]
    fn uniform_rand_anchor() {
        // seed 0, bound 1000 → first index 360 (JavaRandom anchor).
        // padding = max(16 - len("p:")=2, 1) = 14.
        let c = cfg(1000, "p:", "uniform_rand", Some(0));
        let mut g = KeyGenerator::with_seed(&c, 0, SharedCounter::new());
        assert_eq!(g.next_key(), "p:00000000000360");
    }
}
