//! Latency metrics collection.
//!
//! Each worker thread owns its own `MetricsCollector`; the engine merges them
//! after the phase. Latencies are clamped to 600s before recording and errors
//! are counted but not recorded into the histogram — matching the other engines.

use std::collections::BTreeMap;
use std::time::{SystemTime, UNIX_EPOCH};

use hdrhistogram::Histogram;

use crate::command::CommandResult;

use super::hdr::{new_histogram, HIGHEST_TRACKABLE_VALUE};

/// Per-command latency and counters.
pub struct CommandMetrics {
    pub requests: u64,
    pub errors: u64,
    histogram: Histogram<u64>,
}

impl CommandMetrics {
    fn new() -> Self {
        CommandMetrics {
            requests: 0,
            errors: 0,
            // Created eagerly so the NDJSON hdr block and summary are always
            // present, even for a command that only ever errors.
            histogram: new_histogram(),
        }
    }

    fn record(&mut self, result: &CommandResult) {
        self.requests += 1;
        if result.success {
            let latency = result.latency_micros.min(HIGHEST_TRACKABLE_VALUE);
            // record() only fails if the value exceeds the bounds, which the
            // clamp prevents; saturating_record is a defensive fallback.
            self.histogram.saturating_record(latency);
        } else {
            self.errors += 1;
        }
    }

    pub fn histogram(&self) -> &Histogram<u64> {
        &self.histogram
    }

    pub fn count(&self) -> u64 {
        self.histogram.len()
    }

    /// Bucket-equivalent min, matching Java's `getMinValue()`.
    pub fn min(&self) -> u64 {
        self.histogram.value_at_percentile(0.0)
    }

    /// Bucket-equivalent max, matching Java's `getMaxValue()`.
    pub fn max(&self) -> u64 {
        self.histogram.value_at_percentile(100.0)
    }

    pub fn percentile(&self, pct: f64) -> u64 {
        self.histogram.value_at_percentile(pct)
    }

    fn merge_from(&mut self, other: &CommandMetrics) {
        self.requests += other.requests;
        self.errors += other.errors;
        self.histogram
            .add(&other.histogram)
            .expect("histograms share bounds");
    }
}

/// Aggregates command metrics and the phase timing window.
pub struct MetricsCollector {
    // BTreeMap keeps command keys in a stable, sorted order in the output.
    command_metrics: BTreeMap<String, CommandMetrics>,
    pub total_requests: u64,
    pub total_errors: u64,
    start_epoch_millis: Option<u64>,
    end_epoch_millis: Option<u64>,
}

impl MetricsCollector {
    pub fn new() -> Self {
        MetricsCollector {
            command_metrics: BTreeMap::new(),
            total_requests: 0,
            total_errors: 0,
            start_epoch_millis: None,
            end_epoch_millis: None,
        }
    }

    fn now_millis() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }

    pub fn start(&mut self) {
        self.start_epoch_millis = Some(Self::now_millis());
    }

    pub fn stop(&mut self) {
        self.end_epoch_millis = Some(Self::now_millis());
    }

    pub fn start_millis(&self) -> Option<u64> {
        self.start_epoch_millis
    }

    pub fn end_millis(&self) -> Option<u64> {
        self.end_epoch_millis
    }

    pub fn record(&mut self, result: &CommandResult) {
        self.total_requests += 1;
        if !result.success {
            self.total_errors += 1;
        }
        self.command_metrics
            .entry(result.command_name.clone())
            .or_insert_with(CommandMetrics::new)
            .record(result);
    }

    pub fn command_metrics(&self) -> &BTreeMap<String, CommandMetrics> {
        &self.command_metrics
    }

    pub fn duration_millis(&self) -> u64 {
        match (self.start_epoch_millis, self.end_epoch_millis) {
            (Some(s), Some(e)) if e >= s => e - s,
            _ => 0,
        }
    }

    /// Merge another collector into this one, taking the widest time window
    /// (min start / max end) so the phase window spans all workers.
    pub fn merge_from(&mut self, other: &MetricsCollector) {
        self.total_requests += other.total_requests;
        self.total_errors += other.total_errors;
        for (name, metrics) in &other.command_metrics {
            match self.command_metrics.get_mut(name) {
                Some(existing) => existing.merge_from(metrics),
                None => {
                    let mut fresh = CommandMetrics::new();
                    fresh.merge_from(metrics);
                    self.command_metrics.insert(name.clone(), fresh);
                }
            }
        }
        self.start_epoch_millis = min_opt(self.start_epoch_millis, other.start_epoch_millis);
        self.end_epoch_millis = max_opt(self.end_epoch_millis, other.end_epoch_millis);
    }
}

impl Default for MetricsCollector {
    fn default() -> Self {
        Self::new()
    }
}

fn min_opt(a: Option<u64>, b: Option<u64>) -> Option<u64> {
    match (a, b) {
        (Some(x), Some(y)) => Some(x.min(y)),
        (Some(x), None) | (None, Some(x)) => Some(x),
        (None, None) => None,
    }
}

fn max_opt(a: Option<u64>, b: Option<u64>) -> Option<u64> {
    match (a, b) {
        (Some(x), Some(y)) => Some(x.max(y)),
        (Some(x), None) | (None, Some(x)) => Some(x),
        (None, None) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn result(name: &str, latency: u64, success: bool) -> CommandResult {
        CommandResult {
            command_name: name.to_string(),
            latency_micros: latency,
            success,
        }
    }

    #[test]
    fn records_and_counts() {
        let mut c = MetricsCollector::new();
        c.record(&result("GET", 100, true));
        c.record(&result("GET", 300, true));
        c.record(&result("GET", 0, false));
        assert_eq!(c.total_requests, 3);
        assert_eq!(c.total_errors, 1);
        let get = &c.command_metrics()["GET"];
        assert_eq!(get.requests, 3);
        assert_eq!(get.errors, 1);
        assert_eq!(get.count(), 2); // errors not recorded into histogram
    }

    #[test]
    fn merge_takes_widest_window() {
        let mut a = MetricsCollector::new();
        a.start_epoch_millis = Some(100);
        a.end_epoch_millis = Some(200);
        a.record(&result("GET", 100, true));

        let mut b = MetricsCollector::new();
        b.start_epoch_millis = Some(50);
        b.end_epoch_millis = Some(300);
        b.record(&result("GET", 200, true));

        a.merge_from(&b);
        assert_eq!(a.start_millis(), Some(50));
        assert_eq!(a.end_millis(), Some(300));
        assert_eq!(a.command_metrics()["GET"].count(), 2);
        assert_eq!(a.total_requests, 2);
    }
}
