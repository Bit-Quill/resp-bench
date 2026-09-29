//! Top-level workload configuration (metadata + ordered phases).

use serde::Deserialize;

use super::phase::PhaseConfig;

/// Metadata about a benchmark profile.
#[derive(Debug, Clone, Deserialize)]
pub struct BenchmarkProfile {
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub version: Option<String>,
}

/// A workload: profile metadata plus the phases to run in order.
#[derive(Debug, Clone, Deserialize)]
pub struct WorkloadConfig {
    #[serde(default = "default_schema_version")]
    pub schema_version: String,
    pub benchmark_profile: BenchmarkProfile,
    pub phases: Vec<PhaseConfig>,
}

fn default_schema_version() -> String {
    "1.0".to_string()
}

impl WorkloadConfig {
    pub fn name(&self) -> &str {
        &self.benchmark_profile.name
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.phases.is_empty() {
            return Err("workload must contain at least one phase".to_string());
        }
        for phase in &self.phases {
            phase.validate()?;
        }
        Ok(())
    }
}
