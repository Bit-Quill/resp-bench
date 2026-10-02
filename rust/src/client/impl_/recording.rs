//! Server-free in-memory driver for unit/integration tests.
//!
//! Records nothing on the wire; returns a synthetic latency so the engine,
//! metrics, and NDJSON output can be exercised without a live server. An
//! optional `error_rate` in `specific_driver_config` injects failures.
//!
//! For tests that need to inspect the key stream the engine issues, setting
//! `specific_driver_config.capture_id` to a string makes GET/SET keys append to
//! a process-global registry under that id (see [`captured_keys`]). PING records
//! nothing, so a capture also proves PING consumes no key.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

use crate::client::{BenchmarkClient, TimedResult};
use crate::config::DriverConfig;

/// Process-global key-capture registry, keyed by `capture_id`.
fn registry() -> &'static Mutex<HashMap<String, Vec<String>>> {
    static REG: OnceLock<Mutex<HashMap<String, Vec<String>>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Keys captured so far for `capture_id` (test helper).
pub fn captured_keys(capture_id: &str) -> Vec<String> {
    registry()
        .lock()
        .unwrap()
        .get(capture_id)
        .cloned()
        .unwrap_or_default()
}

/// Clear any captured keys for `capture_id` (test helper).
pub fn reset_capture(capture_id: &str) {
    registry().lock().unwrap().remove(capture_id);
}

pub struct RecordingClient {
    counter: AtomicU64,
    latency_micros: u64,
    error_every: u64, // 0 = never error; N = fail 1 in N
    capture_id: Option<String>,
}

impl RecordingClient {
    pub fn new() -> Self {
        RecordingClient {
            counter: AtomicU64::new(0),
            latency_micros: 100,
            error_every: 0,
            capture_id: None,
        }
    }

    fn capture(&self, key: &str) {
        if let Some(id) = &self.capture_id {
            registry()
                .lock()
                .unwrap()
                .entry(id.clone())
                .or_default()
                .push(key.to_string());
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
        if let Some(id) = config
            .specific_driver_config
            .get("capture_id")
            .and_then(|v| v.as_str())
        {
            self.capture_id = Some(id.to_string());
        }
        Ok(())
    }

    fn get(&self, key: &str) -> TimedResult {
        self.capture(key);
        self.record()
    }

    fn set(&self, key: &str, _value: &[u8]) -> TimedResult {
        self.capture(key);
        self.record()
    }

    fn ping(&self) -> TimedResult {
        // Warmup PINGs always succeed for the recording driver. PING records no
        // key, so a key capture reflects only key-consuming commands.
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
