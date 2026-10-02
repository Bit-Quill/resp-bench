//! Benchmark commands (GET/SET/PING) and their metrics-facing results.

mod factory;

pub use factory::{create_all, supported_commands, CommandError};

use crate::client::{BenchmarkClient, TimedResult};
use crate::config::CommandConfig;

const VALUE_PATTERN: &[u8] = b"0123456789ABCDEF";

/// The result of executing one command, tagged with the metrics command name.
pub struct CommandResult {
    pub command_name: String,
    pub latency_micros: u64,
    pub success: bool,
}

/// A ready-to-execute command. Cloneable and `Send + Sync` so one selector can
/// be shared across a connection's worker threads.
#[derive(Clone)]
pub enum Command {
    Get {
        name: String,
        weight: f64,
    },
    Set {
        name: String,
        weight: f64,
        value: Vec<u8>,
    },
    Ping {
        name: String,
        weight: f64,
    },
}

impl Command {
    pub fn weight(&self) -> f64 {
        match self {
            Command::Get { weight, .. }
            | Command::Set { weight, .. }
            | Command::Ping { weight, .. } => *weight,
        }
    }

    pub fn name(&self) -> &str {
        match self {
            Command::Get { name, .. } | Command::Set { name, .. } | Command::Ping { name, .. } => {
                name
            }
        }
    }

    /// Execute against `client`. PING ignores the generated key (matching the
    /// Java reference), so consuming a key for it would shift every later key.
    pub fn execute(&self, client: &dyn BenchmarkClient, key: &str) -> CommandResult {
        let (name, result): (&str, TimedResult) = match self {
            Command::Get { name, .. } => (name, client.get(key)),
            Command::Set { name, value, .. } => (name, client.set(key, value)),
            Command::Ping { name, .. } => (name, client.ping()),
        };
        CommandResult {
            command_name: name.to_string(),
            latency_micros: result.latency_micros,
            success: result.success,
        }
    }
}

/// Whether this command consumes a generated key (PING does not).
pub fn consumes_key(cmd: &Command) -> bool {
    !matches!(cmd, Command::Ping { .. })
}

/// Build the deterministic value payload for SET (repeating pattern, truncated).
pub(crate) fn generate_value(size: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(size);
    while out.len() < size {
        let take = (size - out.len()).min(VALUE_PATTERN.len());
        out.extend_from_slice(&VALUE_PATTERN[..take]);
    }
    out
}

pub(crate) fn build(config: &CommandConfig) -> Result<Command, CommandError> {
    let name = config.command.to_uppercase();
    match config.command.to_lowercase().as_str() {
        "get" => Ok(Command::Get {
            name,
            weight: config.weight,
        }),
        "set" => Ok(Command::Set {
            name,
            weight: config.weight,
            value: generate_value(config.data_size_bytes.unwrap_or(0)),
        }),
        "ping" => Ok(Command::Ping {
            name,
            weight: config.weight,
        }),
        other => Err(CommandError(format!(
            "Unknown command: {other}. Supported: {}",
            supported_commands().join(", ")
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn value_repeats_pattern() {
        assert_eq!(generate_value(4), b"0123");
        assert_eq!(generate_value(16), b"0123456789ABCDEF");
        assert_eq!(&generate_value(18)[..], b"0123456789ABCDEF01");
    }

    #[test]
    fn ping_does_not_consume_key() {
        let ping = Command::Ping {
            name: "PING".into(),
            weight: 1.0,
        };
        assert!(!consumes_key(&ping));
        let get = Command::Get {
            name: "GET".into(),
            weight: 1.0,
        };
        assert!(consumes_key(&get));
    }
}
