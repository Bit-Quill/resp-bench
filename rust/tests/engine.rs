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

    // A 1s duration phase must report a duration_ms close to a second, with a
    // start/finish window to match. This pins the deadline unit: shortening it
    // to from_millis would collapse duration_ms far below this floor.
    let duration_ms = rec["phase"]["duration_ms"].as_u64().unwrap();
    assert!(
        (700..=5000).contains(&duration_ms),
        "duration_ms={duration_ms} not within a 1s phase window"
    );
    let start = rec["phase"]["start_timestamp"].as_str().unwrap();
    let finish = rec["phase"]["finish_timestamp"].as_str().unwrap();
    assert!(start.ends_with('Z') && finish.ends_with('Z'));
    assert!(finish > start, "finish {finish} not after start {start}");
}

#[test]
fn ping_in_sequential_mix_leaves_no_keyspace_holes() {
    // PING consumes no key (matching Java). With a sequential_int populate phase
    // that mixes PING and SET, the SET keys must be a contiguous 0..N prefix of
    // the keyspace — no holes left by PING advancing the counter.
    use resp_bench::client::impl_::recording::{captured_keys, reset_capture};

    let capture_id = "ping-holes-test";
    reset_capture(capture_id);

    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("m.ndjson");
    let out_str = out.to_str().unwrap();

    // One connection, depth 1 → a single deterministic key stream. 50/50 PING/SET
    // over 400 requests ≈ 200 SETs, well under keys_count so no wraparound.
    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"t"},
      "phases":[{
        "id":"POP","connections":1,"pipeline_depth":1,"warmup_requests":0,
        "commands":[{"command":"ping","weight":0.5},{"command":"set","weight":0.5,"data_size_bytes":8}],
        "keyspace":{"keys_count":100000,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":400}
      }]
    }"#;

    let driver = parse_driver_config_str(&recording_driver(&format!(
        r#","specific_driver_config":{{"capture_id":"{capture_id}"}}"#
    )))
    .unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new("localhost", 6379, driver, wl, out_str, None);
    b.run().unwrap();

    let keys = captured_keys(capture_id);
    reset_capture(capture_id);

    // Only SETs captured a key (PING records none). Parse the numeric suffix.
    let mut indices: Vec<u64> = keys
        .iter()
        .map(|k| k.trim_start_matches("k:").trim_start_matches('0'))
        .map(|s| if s.is_empty() { 0 } else { s.parse().unwrap() })
        .collect();
    indices.sort_unstable();

    assert!(!indices.is_empty(), "no keys captured");
    // Contiguous 0, 1, 2, ... with no gaps: PING did not skip any index.
    for (expected, got) in indices.iter().enumerate() {
        assert_eq!(
            *got, expected as u64,
            "keyspace hole at position {expected}: got index {got}"
        );
    }
}

#[test]
fn latency_summary_matches_recorded_values() {
    // Pin the summary wiring: a known latency distribution must map to the right
    // percentile slots. The recording driver's latency is latency_micros + n%50,
    // so with latency_micros=1000 all samples fall in [1000, 1049]. min/p50/max
    // must land in that band and be ordered — a swapped min/max or p50/p999
    // mapping would break these bounds.
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("m.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"t"},
      "phases":[{
        "id":"P","connections":1,"pipeline_depth":1,"warmup_requests":0,
        "commands":[{"command":"get","weight":1.0}],
        "keyspace":{"keys_count":100,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":1000}
      }]
    }"#;

    let driver = parse_driver_config_str(&recording_driver(
        r#","specific_driver_config":{"latency_micros":1000}"#,
    ))
    .unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new("localhost", 6379, driver, wl, out_str, None);
    b.run().unwrap();

    let rec = &read_lines(out_str)[0];
    let latency = &rec["metrics"]["GET"]["latency"];
    assert_eq!(latency["count"].as_u64().unwrap(), 1000);

    let s = &latency["summary"];
    let min = s["min"].as_u64().unwrap();
    let p50 = s["p50"].as_u64().unwrap();
    let p95 = s["p95"].as_u64().unwrap();
    let p99 = s["p99"].as_u64().unwrap();
    let p999 = s["p999"].as_u64().unwrap();
    let max = s["max"].as_u64().unwrap();

    // HDR has 3 sig figs; allow +/-1us slack at these magnitudes.
    assert!((999..=1001).contains(&min), "min={min}");
    assert!((1049..=1051).contains(&max), "max={max}");
    // Ordered and inside the known band — catches min<->max or p50<->p999 swaps.
    assert!(min <= p50 && p50 <= p95 && p95 <= p99 && p99 <= p999 && p999 <= max);
    assert!((1000..=1050).contains(&p50), "p50={p50} outside band");
}

#[test]
fn partial_errors_still_complete_with_errors_counted() {
    // A phase with SOME failures (not all) is COMPLETED, and the errors are
    // counted — not promoted to ERROR. error_rate 0.1 → ~1 in 10 fail.
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("m.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"t"},
      "phases":[{
        "id":"P","connections":2,"warmup_requests":0,
        "commands":[{"command":"get","weight":1.0}],
        "keyspace":{"keys_count":100,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":1000}
      }]
    }"#;

    let driver = parse_driver_config_str(&recording_driver(
        r#","specific_driver_config":{"error_rate":0.1}"#,
    ))
    .unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new("localhost", 6379, driver, wl, out_str, None);
    b.run().unwrap();
    assert!(!b.had_error(), "partial errors must not fail the run");

    let rec = &read_lines(out_str)[0];
    assert_eq!(rec["phase"]["status"], "COMPLETED");
    let errors = rec["totals"]["errors"].as_u64().unwrap();
    let requests = rec["totals"]["requests"].as_u64().unwrap();
    assert_eq!(requests, 1000);
    assert!(
        errors > 0 && errors < requests,
        "errors={errors} of {requests}"
    );
}

#[test]
fn interrupt_sets_interrupted_status_and_skips_later_phases() {
    // A flag set before run() stops at phase boundaries: the first phase reports
    // INTERRUPTED (no requests), later phases are skipped, and the run flags an
    // error. This pins the interrupt-handling the README promises.
    use std::sync::atomic::Ordering;

    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("m.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"t"},
      "phases":[
        {"id":"P1","connections":2,"warmup_requests":0,
         "commands":[{"command":"get","weight":1.0}],
         "keyspace":{"keys_count":100,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
         "completion":{"type":"requests","requests":1000}},
        {"id":"P2","connections":2,"warmup_requests":0,
         "commands":[{"command":"get","weight":1.0}],
         "keyspace":{"keys_count":100,"key_size_bytes":16,"key_prefix":"k:","generation_alg":"sequential_int"},
         "completion":{"type":"requests","requests":1000}}
      ]
    }"#;

    let driver = parse_driver_config_str(&recording_driver("")).unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new("localhost", 6379, driver, wl, out_str, None);
    // Flip the interrupt flag before running: setup bails, no requests run.
    b.interrupt_flag().store(true, Ordering::SeqCst);
    b.run().unwrap();
    assert!(b.had_error(), "an interrupted run must flag an error");

    // Only the first phase is written (the rest are skipped), and it is marked
    // INTERRUPTED rather than ERROR or COMPLETED.
    let lines = read_lines(out_str);
    assert_eq!(lines.len(), 1, "later phases should be skipped");
    assert_eq!(lines[0]["phase"]["status"], "INTERRUPTED");
    assert_eq!(lines[0]["totals"]["requests"].as_u64().unwrap(), 0);
}
