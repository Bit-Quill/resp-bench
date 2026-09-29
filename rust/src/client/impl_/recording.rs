//! Server-free in-memory driver for unit/integration tests.
//!
//! Records nothing on the wire; returns a synthetic latency so the engine,
//! metrics, and NDJSON output can be exercised without a live server. An
//! optional `error_rate` in `specific_driver_config` injects failures.

use std::sync::atomic::{AtomicU64, Ordering};

use crate::client::{BenchmarkClient, TimedResult};
use crate::config::DriverConfig;

pub struct RecordingClient {
    counter: AtomicU64,
    latency_micros: u64,
    error_every: u64, // 0 = never error; N = fail 1 in N
}

impl RecordingClient {
    pub fn new() -> Self {
        RecordingClient {
            counter: AtomicU64::new(0),
            latency_micros: 100,
            error_every: 0,
        }
    }

    fn record(&self) -> TimedResult {
        let n = self.counter.fetch_add(1, Ordering::Relaxed);
        // Deterministic-ish latency spread so histograms have shape.
        let latency = self.latency_micros + (n % 50);
        if self.error_every > 0 && (n + 1).is_multiple_of(self.error_every) {
            TimedResult::err(latency)
        } else {
            TimedResult::ok(latency)
        }
    }
}

impl Default for RecordingClient {
    fn default() -> Self {
        Self::new()
    }
}

impl BenchmarkClient for RecordingClient {
    fn connect(&mut self, _host: &str, _port: u16, config: &DriverConfig) -> Result<(), String> {
        if let Some(rate) = config
            .specific_driver_config
            .get("error_rate")
            .and_then(|v| v.as_f64())
        {
            if rate > 0.0 && rate <= 1.0 {
                self.error_every = (1.0 / rate).round() as u64;
            }
        }
        if let Some(us) = config
            .specific_driver_config
            .get("latency_micros")
            .and_then(|v| v.as_u64())
        {
            self.latency_micros = us;
        }
        Ok(())
    }

    fn get(&self, _key: &str) -> TimedResult {
        self.record()
    }

    fn set(&self, _key: &str, _value: &[u8]) -> TimedResult {
        self.record()
    }

    fn ping(&self) -> TimedResult {
        // Warmup PINGs always succeed for the recording driver.
        TimedResult::ok(self.latency_micros)
    }

    fn close(&mut self) {}

    fn driver_version(&self) -> String {
        "recording".to_string()
    }

    fn driver_details(&self) -> serde_json::Map<String, serde_json::Value> {
        let mut m = serde_json::Map::new();
        m.insert("response_parser".to_string(), "in-memory".into());
        m
    }
}
