//! Engine integration tests using the server-free `recording` driver.

use std::fs;

use resp_bench::config::{parse_driver_config_str, parse_workload_config_str};
use resp_bench::engine::Benchmark;
use serde_json::Value;

fn recording_driver(extra: &str) -> String {
    format!(r#"{{"schema_version":"1.0","driver_id":"recording","mode":"standalone"{extra}}}"#)
}

fn read_lines(path: &str) -> Vec<Value> {
    fs::read_to_string(path)
        .unwrap()
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| serde_json::from_str(l).unwrap())
        .collect()
}

#[test]
fn hits_exact_request_budget_and_schema() {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("m.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"t"},
      "phases":[{
        "id":"STEADY","connections":4,
        "commands":[{"command":"get","weight":0.5},{"command":"set","weight":0.5,"data_size_bytes":32}],
        "keyspace":{"keys_count":100,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":2000}
      }]
    }"#;

    let driver = parse_driver_config_str(&recording_driver("")).unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new(
        "localhost",
        6379,
        driver,
        wl,
        out_str,
        Some("abc123".into()),
    );
    b.run().unwrap();
    assert!(!b.had_error());

    let lines = read_lines(out_str);
    assert_eq!(lines.len(), 1);
    let rec = &lines[0];

    // Exact budget: no overshoot from the shared atomic counter.
    assert_eq!(rec["totals"]["requests"].as_u64().unwrap(), 2000);
    assert_eq!(rec["totals"]["errors"].as_u64().unwrap(), 0);

    // Phase schema + enrichment.
    let phase = &rec["phase"];
    assert_eq!(phase["status"], "COMPLETED");
    assert_eq!(phase["connections"], 4);
    assert_eq!(phase["pipeline_depth"], 1);
    assert_eq!(phase["sockets_per_client"], 1);
    assert_eq!(phase["total_sockets"], 4);
    assert!(phase["start_timestamp"].is_string());
    assert!(phase["finish_timestamp"].is_string());

    // Metadata carries commit + driver.
    assert_eq!(rec["metadata"]["commit_id"], "abc123");
    assert_eq!(rec["metadata"]["driver_id"], "recording");

    // Command metrics have the full latency block.
    let get = &rec["metrics"]["GET"];
    assert!(get["requests"].as_u64().unwrap() > 0);
    let latency = &get["latency"];
    assert_eq!(latency["unit"], "us");
    assert!(latency["summary"]["p50"].is_number());
    assert_eq!(latency["hdr"]["format"], "hdr");
    assert!(!latency["hdr"]["payload_b64"].as_str().unwrap().is_empty());
}

#[test]
fn pipeline_depth_reports_multiplexed_sockets() {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("m.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"t"},
      "phases":[{
        "id":"P","connections":2,"pipeline_depth":8,
        "commands":[{"command":"get","weight":1.0}],
        "keyspace":{"keys_count":50,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":1600}
      }]
    }"#;

    let driver = parse_driver_config_str(&recording_driver("")).unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new("localhost", 6379, driver, wl, out_str, None);
    b.run().unwrap();

    let rec = &read_lines(out_str)[0];
    assert_eq!(rec["totals"]["requests"].as_u64().unwrap(), 1600);
    // The recording driver is multiplexing-like: 1 socket regardless of depth.
    assert_eq!(rec["phase"]["pipeline_depth"], 8);
    assert_eq!(rec["phase"]["sockets_per_client"], 1);
    assert_eq!(rec["phase"]["total_sockets"], 2);
}

#[test]
fn all_failures_report_error() {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("m.ndjson");
    let out_str = out.to_str().unwrap();

    // error_rate 1.0 → every command fails. Warmup PING still succeeds for the
    // recording driver, so the phase runs and then reports ERROR (all failed).
    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"t"},
      "phases":[{
        "id":"P","connections":2,"warmup_requests":0,
        "commands":[{"command":"get","weight":1.0}],
        "keyspace":{"keys_count":10,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":100}
      }]
    }"#;

    let driver = parse_driver_config_str(&recording_driver(
        r#","specific_driver_config":{"error_rate":1.0}"#,
    ))
    .unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new("localhost", 6379, driver, wl, out_str, None);
    b.run().unwrap();
    assert!(b.had_error());

    let rec = &read_lines(out_str)[0];
    assert_eq!(rec["phase"]["status"], "ERROR");
    assert_eq!(rec["totals"]["errors"], rec["totals"]["requests"]);
}

#[test]
fn rps_limit_is_a_global_cap() {
    // With connections=4 and rps_limit=2000, the WHOLE phase should run at ~2000
    // rps (not 4x that). 2000 requests at 2000 rps ≈ 1s. A per-connection limiter
    // bug would finish it in ~0.25s. Allow generous slack for CI timing.
    use std::time::Instant;

    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("m.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"t"},
      "phases":[{
        "id":"P","connections":4,"rps_limit":2000,"warmup_requests":0,
        "commands":[{"command":"get","weight":1.0}],
        "keyspace":{"keys_count":100,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":2000}
      }]
    }"#;

    let driver = parse_driver_config_str(&recording_driver("")).unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new("localhost", 6379, driver, wl, out_str, None);

    let start = Instant::now();
    b.run().unwrap();
    let elapsed = start.elapsed();

    let rec = &read_lines(out_str)[0];
    assert_eq!(rec["totals"]["requests"].as_u64().unwrap(), 2000);
    // 2000 req / 2000 rps ≈ 1s. A 4x-too-fast bug would finish well under 0.5s.
    assert!(
        elapsed.as_millis() >= 700,
        "phase finished in {:?}; rps_limit not enforced as a global cap",
        elapsed
    );
}

#[test]
fn duration_based_phase_completes() {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("m.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"t"},
      "phases":[{
        "id":"D","connections":2,
        "commands":[{"command":"ping","weight":1.0}],
        "keyspace":{"keys_count":10,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
        "completion":{"type":"duration","seconds":1}
      }]
    }"#;

    let driver = parse_driver_config_str(&recording_driver("")).unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new("localhost", 6379, driver, wl, out_str, None);
    b.run().unwrap();
    assert!(!b.had_error());

    let rec = &read_lines(out_str)[0];
    assert_eq!(rec["phase"]["status"], "COMPLETED");
    assert!(rec["totals"]["requests"].as_u64().unwrap() > 0);
    // PING metrics recorded (PING consumes no key, but is still measured).
    assert!(rec["metrics"]["PING"]["requests"].as_u64().unwrap() > 0);
}
