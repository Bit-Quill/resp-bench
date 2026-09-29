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
