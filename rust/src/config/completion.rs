//! Phase completion criteria (duration- or request-based).

use serde::Deserialize;

/// How a phase decides it is done.
#[derive(Debug, Clone, Deserialize)]
pub struct CompletionConfig {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub seconds: Option<u64>,
    #[serde(default)]
    pub requests: Option<u64>,
}

impl CompletionConfig {
    pub fn is_duration_based(&self) -> bool {
        self.kind == "duration"
    }

    pub fn duration_seconds(&self) -> u64 {
        self.seconds.unwrap_or(0)
    }

    pub fn total_requests(&self) -> u64 {
        self.requests.unwrap_or(0)
    }

    pub fn validate(&self) -> Result<(), String> {
        match self.kind.as_str() {
            "duration" => {
                if self.duration_seconds() < 1 {
                    return Err("completion.seconds must be >= 1 for duration".to_string());
                }
            }
            "requests" => {
                if self.total_requests() < 1 {
                    return Err("completion.requests must be >= 1 for requests".to_string());
                }
            }
            other => {
                return Err(format!(
                    "Unknown completion.type: {other} (expected \"duration\" or \"requests\")"
                ))
            }
        }
        Ok(())
    }
}
