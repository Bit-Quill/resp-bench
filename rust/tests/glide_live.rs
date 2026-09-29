//! Live GLIDE integration tests. Ignored by default (require a running server);
//! run with `cargo test -- --ignored` and `VALKEY_HOST`/`VALKEY_PORT` set.

use std::fs;

use resp_bench::config::{parse_driver_config_str, parse_workload_config_str};
use resp_bench::engine::Benchmark;
use serde_json::Value;

fn live_server() -> Option<(String, u16)> {
    let host = std::env::var("VALKEY_HOST").ok()?;
    let port = std::env::var("VALKEY_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(6379);
    Some((host, port))
}

fn read_first(path: &str) -> Value {
    let line = fs::read_to_string(path)
        .unwrap()
        .lines()
        .find(|l| !l.trim().is_empty())
        .unwrap()
        .to_string();
    serde_json::from_str(&line).unwrap()
}

#[test]
#[ignore = "requires a live Valkey server (VALKEY_HOST)"]
fn glide_live_benchmark_multiplexes() {
    let (host, port) = match live_server() {
        Some(v) => v,
        None => {
            eprintln!("VALKEY_HOST not set; skipping live test");
            return;
        }
    };

    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("glide.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"glide-live"},
      "phases":[{
        "id":"STEADY","connections":2,"pipeline_depth":4,"warmup_requests":2,
        "commands":[{"command":"get","weight":0.7},{"command":"set","weight":0.3,"data_size_bytes":64}],
        "keyspace":{"keys_count":500,"key_size_bytes":16,"key_prefix":"glidetest:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":4000}
      }]
    }"#;

    let driver = parse_driver_config_str(
        r#"{"schema_version":"1.0","driver_id":"valkey-glide-rust","mode":"standalone"}"#,
    )
    .unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new(host, port, driver, wl, out_str, None);
    b.run().unwrap();
    assert!(!b.had_error());

    let rec = read_first(out_str);
    assert_eq!(rec["phase"]["status"], "COMPLETED");
    assert_eq!(rec["totals"]["requests"].as_u64().unwrap(), 4000);
    assert_eq!(rec["totals"]["errors"].as_u64().unwrap(), 0);
    // GLIDE multiplexes: 1 socket per client even at pipeline_depth 4.
    assert_eq!(rec["phase"]["sockets_per_client"], 1);
    assert_eq!(rec["phase"]["pipeline_depth"], 4);
}

#[test]
#[ignore = "requires a live Valkey server (VALKEY_HOST)"]
fn glide_connect_failure_reports_error() {
    // Connecting to a dead port must fail loudly (ERROR, non-zero), not silently
    // report success. Uses a port with no listener.
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("dead.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"dead"},
      "phases":[{
        "id":"P","connections":2,
        "commands":[{"command":"get","weight":1.0}],
        "keyspace":{"keys_count":10,"key_size_bytes":16,"key_prefix":"x:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":10}
      }]
    }"#;

    let driver = parse_driver_config_str(
        r#"{"schema_version":"1.0","driver_id":"valkey-glide-rust","mode":"standalone","command_timeout_ms":500}"#,
    )
    .unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    // Port 1 has no listener.
    let mut b = Benchmark::new("127.0.0.1", 1, driver, wl, out_str, None);
    b.run().unwrap();
    assert!(b.had_error());
    let rec = read_first(out_str);
    assert_eq!(rec["phase"]["status"], "ERROR");
}

#[test]
#[ignore = "requires a live Valkey server (VALKEY_HOST)"]
fn redis_rs_live_benchmark_pools() {
    let (host, port) = match live_server() {
        Some(v) => v,
        None => {
            eprintln!("VALKEY_HOST not set; skipping live test");
            return;
        }
    };

    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("redisrs.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"redis-rs-live"},
      "phases":[{
        "id":"STEADY","connections":2,"pipeline_depth":8,"warmup_requests":2,
        "commands":[{"command":"get","weight":0.7},{"command":"set","weight":0.3,"data_size_bytes":64}],
        "keyspace":{"keys_count":500,"key_size_bytes":16,"key_prefix":"redisrstest:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":4000}
      }]
    }"#;

    let driver = parse_driver_config_str(
        r#"{"schema_version":"1.0","driver_id":"redis-rs","mode":"standalone"}"#,
    )
    .unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new(host, port, driver, wl, out_str, None);
    b.run().unwrap();
    assert!(!b.had_error());

    let rec = read_first(out_str);
    assert_eq!(rec["phase"]["status"], "COMPLETED");
    assert_eq!(rec["totals"]["requests"].as_u64().unwrap(), 4000);
    assert_eq!(rec["totals"]["errors"].as_u64().unwrap(), 0);
    // redis-rs pools: one socket per in-flight slot, so depth 8 → 8 sockets.
    assert_eq!(rec["phase"]["pipeline_depth"], 8);
    assert_eq!(rec["phase"]["sockets_per_client"], 8);
    assert_eq!(rec["phase"]["total_sockets"], 16);
}
