//! Configuration for a single benchmark phase.

use serde::Deserialize;

use super::command::CommandConfig;
use super::completion::CompletionConfig;
use super::keyspace::KeyspaceConfig;

pub const DEFAULT_PIPELINE_DEPTH: u32 = 1;
pub const DEFAULT_WARMUP_REQUESTS: u32 = 1;

/// One phase of a workload.
#[derive(Debug, Clone, Deserialize)]
pub struct PhaseConfig {
    pub id: String,
    #[serde(default)]
    pub description: Option<String>,
    pub connections: u32,
    #[serde(default = "default_cps")]
    pub cps_limit: i64,
    #[serde(default = "default_rps")]
    pub rps_limit: i64,
    #[serde(default = "default_pipeline_depth")]
    pub pipeline_depth: u32,
    #[serde(default = "default_warmup")]
    pub warmup_requests: u32,
    pub completion: CompletionConfig,
    pub keyspace: KeyspaceConfig,
    pub commands: Vec<CommandConfig>,
}

fn default_cps() -> i64 {
    -1
}

fn default_rps() -> i64 {
    -1
}

fn default_pipeline_depth() -> u32 {
    DEFAULT_PIPELINE_DEPTH
}

fn default_warmup() -> u32 {
    DEFAULT_WARMUP_REQUESTS
}

impl PhaseConfig {
    pub fn has_cps_limit(&self) -> bool {
        self.cps_limit > 0
    }

    pub fn has_rps_limit(&self) -> bool {
        self.rps_limit > 0
    }

    pub fn effective_pipeline_depth(&self) -> u32 {
        if self.pipeline_depth > 0 {
            self.pipeline_depth
        } else {
            DEFAULT_PIPELINE_DEPTH
        }
    }

    pub fn description(&self) -> &str {
        self.description.as_deref().unwrap_or("")
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.connections < 1 {
            return Err(format!("phase {}: connections must be >= 1", self.id));
        }
        if self.commands.is_empty() {
            return Err(format!(
                "phase {}: at least one command is required",
                self.id
            ));
        }
        self.keyspace.validate()?;
        self.completion.validate()?;
        Ok(())
    }
}
