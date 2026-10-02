//! Key-generation configuration for a benchmark phase.

use serde::Deserialize;

pub const DEFAULT_KEY_SIZE_BYTES: usize = 16;
pub const DEFAULT_KEY_PREFIX: &str = "bench:";

/// Key generation configuration.
#[derive(Debug, Clone, Deserialize)]
pub struct KeyspaceConfig {
    pub keys_count: u64,
    #[serde(default = "default_key_size")]
    pub key_size_bytes: usize,
    #[serde(default = "default_key_prefix")]
    pub key_prefix: String,
    #[serde(default = "default_generation_alg")]
    pub generation_alg: String,
    #[serde(default)]
    pub seed: Option<i64>,
}

fn default_key_size() -> usize {
    DEFAULT_KEY_SIZE_BYTES
}

fn default_key_prefix() -> String {
    DEFAULT_KEY_PREFIX.to_string()
}

fn default_generation_alg() -> String {
    "sequential_int".to_string()
}

impl KeyspaceConfig {
    /// Reject a keyspace that would crash the key generator mid-run.
    pub fn validate(&self) -> Result<(), String> {
        if self.keys_count < 1 {
            return Err("keyspace.keys_count must be a positive integer".to_string());
        }
        if self.key_size_bytes < 1 {
            return Err("keyspace.key_size_bytes must be a positive integer".to_string());
        }
        if self.generation_alg != "sequential_int" && self.generation_alg != "uniform_rand" {
            return Err(format!(
                "Unknown keyspace.generation_alg: {} (expected \"sequential_int\" or \"uniform_rand\")",
                self.generation_alg
            ));
        }
        Ok(())
    }

    pub fn is_sequential_int(&self) -> bool {
        self.generation_alg == "sequential_int"
    }

    /// Seed for `uniform_rand`; defaults to 0 when unspecified (matches peers).
    pub fn seed_value(&self) -> i64 {
        self.seed.unwrap_or(0)
    }
}
