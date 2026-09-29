//! Writes benchmark metrics as NDJSON (one JSON object per phase).
//!
//! The output schema matches every other engine exactly (see
//! docs/CONFIG_SPECIFICATION.md): `metadata` / `phase` / `totals` / `metrics`
//! blocks, latency `unit: "us"`, integer `summary` percentiles, uppercased
//! command keys, and an HDR block with the base64 compressed payload. An empty
//! metrics map serializes as `{}`.

use std::fs::OpenOptions;
use std::io::Write;
use std::path::{Path, PathBuf};

use serde_json::{json, Map, Value};

use super::collector::MetricsCollector;
use super::hdr::encode_base64;

/// Serializes phase results to an NDJSON file.
pub struct NdjsonWriter {
    output_path: PathBuf,
    commit_id: Option<String>,
    driver_id: Option<String>,
    primary_driver_version: Option<String>,
    secondary_driver_id: Option<String>,
    secondary_driver_version: Option<String>,
    driver_details: Map<String, Value>,
}

/// Metadata describing what produced a run.
pub struct Metadata {
    pub commit_id: Option<String>,
    pub driver_id: Option<String>,
    pub primary_driver_version: Option<String>,
    pub secondary_driver_id: Option<String>,
    pub secondary_driver_version: Option<String>,
    pub driver_details: Map<String, Value>,
}

/// Build metadata from a connected sample client.
pub fn build_metadata(
    driver_config: &crate::config::DriverConfig,
    commit_id: Option<String>,
    client: &dyn crate::client::BenchmarkClient,
) -> Metadata {
    Metadata {
        commit_id,
        driver_id: Some(driver_config.driver_id.clone()),
        primary_driver_version: Some(client.driver_version()),
        secondary_driver_id: driver_config.secondary_driver_id(),
        secondary_driver_version: None,
        driver_details: client.driver_details(),
    }
}

impl NdjsonWriter {
    pub fn new(path: impl AsRef<Path>) -> Self {
        NdjsonWriter {
            output_path: path.as_ref().to_path_buf(),
            commit_id: None,
            driver_id: None,
            primary_driver_version: None,
            secondary_driver_id: None,
            secondary_driver_version: None,
            driver_details: Map::new(),
        }
    }

    pub fn set_metadata(&mut self, metadata: Metadata) {
        self.commit_id = metadata.commit_id;
        self.driver_id = metadata.driver_id;
        self.primary_driver_version = metadata.primary_driver_version;
        self.secondary_driver_id = metadata.secondary_driver_id;
        self.secondary_driver_version = metadata.secondary_driver_version;
        self.driver_details = metadata.driver_details;
    }

    /// Append one phase's results as an NDJSON line.
    pub fn write_phase_results(
        &self,
        phase_id: &str,
        status: &str,
        connections: u32,
        collector: &MetricsCollector,
        pipeline_depth: u32,
        sockets_per_client: u32,
    ) -> std::io::Result<()> {
        if let Some(parent) = self.output_path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent)?;
            }
        }

        let payload = self.build_phase_json(
            phase_id,
            status,
            connections,
            collector,
            pipeline_depth,
            sockets_per_client,
        );

        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.output_path)?;
        writeln!(file, "{}", serde_json::to_string(&payload)?)?;
        Ok(())
    }

    fn build_phase_json(
        &self,
        phase_id: &str,
        status: &str,
        connections: u32,
        collector: &MetricsCollector,
        pipeline_depth: u32,
        sockets_per_client: u32,
    ) -> Value {
        let mut result = Map::new();

        if self.commit_id.is_some() || self.driver_id.is_some() {
            let mut metadata = Map::new();
            if let Some(commit) = &self.commit_id {
                metadata.insert("commit_id".into(), commit.clone().into());
            }
            metadata.insert("timestamp".into(), iso8601_now().into());
            if let Some(driver) = &self.driver_id {
                metadata.insert("driver_id".into(), driver.clone().into());
            }
            if let Some(v) = &self.primary_driver_version {
                metadata.insert("primary_driver_version".into(), v.clone().into());
            }
            if let Some(v) = &self.secondary_driver_id {
                metadata.insert("secondary_driver_id".into(), v.clone().into());
            }
            if let Some(v) = &self.secondary_driver_version {
                metadata.insert("secondary_driver_version".into(), v.clone().into());
            }
            // Additive, optional fields (negotiated protocol, parser, retries).
            for (key, value) in &self.driver_details {
                metadata.entry(key.clone()).or_insert(value.clone());
            }
            result.insert("metadata".into(), Value::Object(metadata));
        }

        result.insert(
            "phase".into(),
            json!({
                "id": phase_id,
                "status": status,
                "start_timestamp": iso8601_from_millis(collector.start_millis()),
                "finish_timestamp": iso8601_from_millis(collector.end_millis()),
                "duration_ms": collector.duration_millis(),
                "connections": connections,
                "pipeline_depth": pipeline_depth,
                "sockets_per_client": sockets_per_client,
                "total_sockets": connections * sockets_per_client,
            }),
        );

        result.insert(
            "totals".into(),
            json!({
                "requests": collector.total_requests,
                "errors": collector.total_errors,
            }),
        );

        result.insert("metrics".into(), self.build_command_metrics(collector));
        Value::Object(result)
    }

    fn build_command_metrics(&self, collector: &MetricsCollector) -> Value {
        let mut metrics = Map::new();
        for (name, cmd) in collector.command_metrics() {
            let latency = json!({
                "unit": "us",
                "count": cmd.count(),
                "summary": {
                    "min": cmd.min(),
                    "p50": cmd.percentile(50.0),
                    "p95": cmd.percentile(95.0),
                    "p99": cmd.percentile(99.0),
                    "p999": cmd.percentile(99.9),
                    "max": cmd.max(),
                },
                "hdr": {
                    "format": "hdr",
                    "sigfig": 3,
                    "payload_b64": encode_base64(cmd.histogram()),
                },
            });
            metrics.insert(
                name.clone(),
                json!({
                    "requests": cmd.requests,
                    "errors": cmd.errors,
                    "latency": latency,
                }),
            );
        }
        Value::Object(metrics)
    }
}

/// Format epoch-millis as ISO-8601 UTC with a trailing `Z`.
fn iso8601_from_millis(millis: Option<u64>) -> Value {
    match millis {
        Some(ms) => Value::String(format_iso8601(ms)),
        None => Value::Null,
    }
}

fn iso8601_now() -> String {
    let ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    format_iso8601(ms)
}

/// Minimal civil-time conversion from epoch millis to `YYYY-MM-DDTHH:MM:SS.mmmZ`.
/// Avoids a chrono dependency; correct for all dates via the standard
/// days-from-civil algorithm.
fn format_iso8601(epoch_millis: u64) -> String {
    let total_secs = epoch_millis / 1000;
    let millis = epoch_millis % 1000;
    let days = (total_secs / 86_400) as i64;
    let secs_of_day = total_secs % 86_400;
    let hour = secs_of_day / 3600;
    let minute = (secs_of_day % 3600) / 60;
    let second = secs_of_day % 60;

    let (year, month, day) = civil_from_days(days);
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}.{:03}Z",
        year, month, day, hour, minute, second, millis
    )
}

// Howard Hinnant's days-from-civil inverse (public-domain algorithm).
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32; // [1, 12]
    let year = if m <= 2 { y + 1 } else { y };
    (year, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epoch_formats_correctly() {
        // 0 → Unix epoch.
        assert_eq!(format_iso8601(0), "1970-01-01T00:00:00.000Z");
        // 1_700_000_000_000 ms = 2023-11-14T22:13:20.000Z
        assert_eq!(
            format_iso8601(1_700_000_000_000),
            "2023-11-14T22:13:20.000Z"
        );
    }

    #[test]
    fn empty_metrics_is_object() {
        let writer = NdjsonWriter::new("/tmp/unused.ndjson");
        let collector = MetricsCollector::new();
        let value = writer.build_command_metrics(&collector);
        assert_eq!(value, json!({}));
    }
}
