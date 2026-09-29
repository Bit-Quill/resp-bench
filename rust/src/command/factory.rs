//! Maps command config entries to executable commands.

use std::fmt;

use crate::config::CommandConfig;

use super::{build, Command};

/// An unknown/unsupported command.
#[derive(Debug)]
pub struct CommandError(pub String);

impl fmt::Display for CommandError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for CommandError {}

/// Command names this engine can execute.
pub fn supported_commands() -> Vec<&'static str> {
    vec!["ping", "get", "set"]
}

/// Build all commands for a phase, preserving order.
pub fn create_all(configs: &[CommandConfig]) -> Result<Vec<Command>, CommandError> {
    configs.iter().map(build).collect()
}
