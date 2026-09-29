//! The metrics-facing result of one timed command execution.

/// Outcome of a single command: its latency and whether it succeeded.
#[derive(Debug, Clone)]
pub struct TimedResult {
    pub latency_micros: u64,
    pub success: bool,
}

impl TimedResult {
    pub fn ok(latency_micros: u64) -> Self {
        TimedResult {
            latency_micros,
            success: true,
        }
    }

    pub fn err(latency_micros: u64) -> Self {
        TimedResult {
            latency_micros,
            success: false,
        }
    }
}
