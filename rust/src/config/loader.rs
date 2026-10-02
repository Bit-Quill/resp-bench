//! Load and validate driver/workload configs from JSON files.

use std::fmt;
use std::path::Path;

use super::driver::DriverConfig;
use super::workload::WorkloadConfig;

/// A configuration load or validation error.
#[derive(Debug)]
pub enum ConfigError {
    Io(std::io::Error),
    Parse(serde_json::Error),
    Invalid(String),
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ConfigError::Io(e) => write!(f, "I/O error: {e}"),
            ConfigError::Parse(e) => write!(f, "JSON parse error: {e}"),
            ConfigError::Invalid(m) => write!(f, "invalid configuration: {m}"),
        }
    }
}

impl std::error::Error for ConfigError {}

impl From<std::io::Error> for ConfigError {
    fn from(e: std::io::Error) -> Self {
        ConfigError::Io(e)
    }
}

impl From<serde_json::Error> for ConfigError {
    fn from(e: serde_json::Error) -> Self {
        ConfigError::Parse(e)
    }
}

/// Parse a driver config from a JSON string.
pub fn parse_driver_config_str(json: &str) -> Result<DriverConfig, ConfigError> {
    Ok(serde_json::from_str(json)?)
}

/// Parse and validate a workload config from a JSON string.
pub fn parse_workload_config_str(json: &str) -> Result<WorkloadConfig, ConfigError> {
    let workload: WorkloadConfig = serde_json::from_str(json)?;
    workload.validate().map_err(ConfigError::Invalid)?;
    Ok(workload)
}

/// Load a driver config from a file path.
pub fn load_driver_config(path: impl AsRef<Path>) -> Result<DriverConfig, ConfigError> {
    let data = std::fs::read_to_string(path)?;
    parse_driver_config_str(&data)
}

/// Load and validate a workload config from a file path.
pub fn load_workload_config(path: impl AsRef<Path>) -> Result<WorkloadConfig, ConfigError> {
    let data = std::fs::read_to_string(path)?;
    parse_workload_config_str(&data)
}

#[cfg(test)]
mod tests {
    use super::*;

    // A valid workload with the given phase body spliced in, so each test varies
    // exactly one field.
    fn workload_with(phase: &str) -> String {
        format!(
            r#"{{"schema_version":"1.0","benchmark_profile":{{"name":"t"}},"phases":[{phase}]}}"#
        )
    }

    fn ok_phase() -> &'static str {
        r#"{"id":"P","connections":1,
            "commands":[{"command":"get","weight":1.0}],
            "keyspace":{"keys_count":10,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
            "completion":{"type":"requests","requests":100}}"#
    }

    fn err_contains(json: &str, needle: &str) {
        match parse_workload_config_str(json) {
            Ok(_) => panic!("expected validation error containing {needle:?}, got Ok"),
            Err(ConfigError::Invalid(m)) => {
                assert!(m.contains(needle), "error {m:?} missing {needle:?}")
            }
            Err(other) => panic!("expected Invalid, got {other:?}"),
        }
    }

    #[test]
    fn valid_workload_passes() {
        assert!(parse_workload_config_str(&workload_with(ok_phase())).is_ok());
    }

    #[test]
    fn rejects_zero_connections() {
        let phase = r#"{"id":"P","connections":0,
            "commands":[{"command":"get","weight":1.0}],
            "keyspace":{"keys_count":10,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
            "completion":{"type":"requests","requests":100}}"#;
        err_contains(&workload_with(phase), "connections");
    }

    #[test]
    fn rejects_empty_commands() {
        let phase = r#"{"id":"P","connections":1,
            "commands":[],
            "keyspace":{"keys_count":10,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
            "completion":{"type":"requests","requests":100}}"#;
        err_contains(&workload_with(phase), "command");
    }

    #[test]
    fn rejects_zero_keys_count() {
        // The exact bug the validation guards: keys_count 0 would later panic in
        // KeyGenerator (`% keys_count`) mid-run. It must fail at load time.
        let phase = r#"{"id":"P","connections":1,
            "commands":[{"command":"get","weight":1.0}],
            "keyspace":{"keys_count":0,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
            "completion":{"type":"requests","requests":100}}"#;
        err_contains(&workload_with(phase), "keys_count");
    }

    #[test]
    fn rejects_unknown_generation_alg() {
        let phase = r#"{"id":"P","connections":1,
            "commands":[{"command":"get","weight":1.0}],
            "keyspace":{"keys_count":10,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"zipfian"},
            "completion":{"type":"requests","requests":100}}"#;
        err_contains(&workload_with(phase), "generation_alg");
    }

    #[test]
    fn rejects_unknown_completion_type() {
        let phase = r#"{"id":"P","connections":1,
            "commands":[{"command":"get","weight":1.0}],
            "keyspace":{"keys_count":10,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
            "completion":{"type":"forever"}}"#;
        err_contains(&workload_with(phase), "completion.type");
    }

    #[test]
    fn rejects_empty_phases() {
        let json = r#"{"schema_version":"1.0","benchmark_profile":{"name":"t"},"phases":[]}"#;
        err_contains(json, "phase");
    }
}
