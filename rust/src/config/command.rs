//! A single command entry in a phase (name, weight, optional value size).

use serde::Deserialize;

/// Command configuration.
#[derive(Debug, Clone, Deserialize)]
pub struct CommandConfig {
    pub command: String,
    pub weight: f64,
    #[serde(default)]
    pub data_size_bytes: Option<usize>,
}
