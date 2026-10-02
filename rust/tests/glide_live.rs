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

/// Server-observed count of currently connected clients (`INFO clients`), via a
/// separate control connection that is itself excluded from the returned count.
fn server_connected_clients(host: &str, port: u16) -> i64 {
    use redis::ConnectionLike;
    let url = format!("redis://{host}:{port}/");
    let client = redis::Client::open(url).expect("control client");
    let mut conn = client.get_connection().expect("control connection");
    let info: String = redis::cmd("INFO")
        .arg("clients")
        .query(&mut conn)
        .expect("INFO clients");
    let connected = info
        .lines()
        .find_map(|l| l.strip_prefix("connected_clients:"))
        .and_then(|v| v.trim().parse::<i64>().ok())
        .expect("connected_clients field");
    // Subtract this control connection so the number reflects the benchmark's
    // sockets only.
    let _ = conn.req_packed_command(&redis::cmd("PING").get_packed_command());
    connected - 1
}

/// Run the benchmark while a background thread polls the server's client count,
/// returning the peak observed. The poller stops when `run()` returns.
fn run_with_peak_sampling(b: &mut Benchmark, host: &str, port: u16) -> i64 {
    use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
    use std::sync::Arc;

    let done = Arc::new(AtomicBool::new(false));
    let peak = Arc::new(AtomicI64::new(0));
    let host = host.to_string();

    let sampler = {
        let done = done.clone();
        let peak = peak.clone();
        std::thread::spawn(move || {
            while !done.load(Ordering::Relaxed) {
                let now = server_connected_clients(&host, port);
                let mut cur = peak.load(Ordering::Relaxed);
                while now > cur {
                    match peak.compare_exchange(cur, now, Ordering::Relaxed, Ordering::Relaxed) {
                        Ok(_) => break,
                        Err(actual) => cur = actual,
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(5));
            }
        })
    };

    b.run().unwrap();
    done.store(true, Ordering::Relaxed);
    sampler.join().unwrap();
    peak.load(Ordering::Relaxed)
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

    // GLIDE multiplexing means the server-side socket count is a function of the
    // connection count, NOT of pipeline_depth. Run the SAME workload at depth 1
    // and depth 8 and assert the server's extra sockets barely move — a pooling
    // driver would open ~8x more at depth 8. This measures the real wire, not
    // the pipeline_depth value echoed into the metadata.
    let run_at_depth = |depth: u32| -> (serde_json::Value, i64) {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("glide.ndjson");
        let out_str = out.to_str().unwrap().to_string();
        let workload = format!(
            r#"{{
              "schema_version":"1.0",
              "benchmark_profile":{{"name":"glide-live"}},
              "phases":[{{
                "id":"STEADY","connections":2,"pipeline_depth":{depth},"warmup_requests":2,
                "commands":[{{"command":"get","weight":0.7}},{{"command":"set","weight":0.3,"data_size_bytes":64}}],
                "keyspace":{{"keys_count":500,"key_size_bytes":16,"key_prefix":"glidetest:","generation_alg":"sequential_int"}},
                "completion":{{"type":"requests","requests":4000}}
              }}]
            }}"#
        );
        let driver = parse_driver_config_str(
            r#"{"schema_version":"1.0","driver_id":"valkey-glide-rust","mode":"standalone"}"#,
        )
        .unwrap();
        let wl = parse_workload_config_str(&workload).unwrap();
        let mut b = Benchmark::new(host.clone(), port, driver, wl, &out_str, None);
        let baseline = server_connected_clients(&host, port);
        let peak = run_with_peak_sampling(&mut b, &host, port);
        assert!(!b.had_error());
        (read_first(&out_str), peak - baseline)
    };

    let (rec1, sockets_d1) = run_at_depth(1);
    let (rec8, sockets_d8) = run_at_depth(8);

    assert_eq!(rec8["phase"]["status"], "COMPLETED");
    assert_eq!(rec8["totals"]["requests"].as_u64().unwrap(), 4000);
    assert_eq!(rec8["totals"]["errors"].as_u64().unwrap(), 0);
    // GLIDE multiplexes: metadata reports 1 socket per client at any depth.
    assert_eq!(rec1["phase"]["sockets_per_client"], 1);
    assert_eq!(rec8["phase"]["sockets_per_client"], 1);
    assert_eq!(rec8["phase"]["pipeline_depth"], 8);

    // The decisive check: 8x the in-flight depth does NOT give ~8x the sockets.
    // A pooling driver at depth 8 would open ~8x the depth-1 count; GLIDE's
    // socket count is essentially flat in depth.
    assert!(
        sockets_d8 <= sockets_d1 + 2 && sockets_d8 < sockets_d1 * 4,
        "GLIDE sockets scaled with depth: depth1={sockets_d1}, depth8={sockets_d8}"
    );
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
    let mut b = Benchmark::new(host.clone(), port, driver, wl, out_str, None);

    let baseline = server_connected_clients(&host, port);
    let peak = run_with_peak_sampling(&mut b, &host, port);
    assert!(!b.had_error());

    let rec = read_first(out_str);
    assert_eq!(rec["phase"]["status"], "COMPLETED");
    assert_eq!(rec["totals"]["requests"].as_u64().unwrap(), 4000);
    assert_eq!(rec["totals"]["errors"].as_u64().unwrap(), 0);
    // redis-rs pools: one socket per in-flight slot, so depth 8 → 8 sockets.
    assert_eq!(rec["phase"]["pipeline_depth"], 8);
    assert_eq!(rec["phase"]["sockets_per_client"], 8);
    assert_eq!(rec["phase"]["total_sockets"], 16);
    // Server-observed: the pool really opens connections x depth = 16 sockets
    // (primed before the timed window), not just the number echoed into the
    // metadata. A max_size(1) pool or a missing prime would fall far short.
    let opened = peak - baseline;
    assert!(
        opened >= 14,
        "redis-rs opened only {opened} sockets; expected ~16 (2 connections x depth 8)"
    );
}

#[test]
#[ignore = "requires a live Valkey server (VALKEY_HOST)"]
fn redis_rs_command_timeout_bounds_a_stalled_server() {
    // With command_timeout_ms set, a server stall (CLIENT PAUSE) must surface as
    // bounded errors, not an unbounded wait recorded as a success past the phase
    // deadline. This exercises the socket read/write timeout, which r2d2's
    // connection_timeout alone does not provide.
    let (host, port) = match live_server() {
        Some(v) => v,
        None => return,
    };

    // Pause WRITES on the server for 4s via a control connection. WRITE mode
    // lets connection handshakes and the pool's PING-based checks proceed, so
    // the stall lands on the workload's SET commands — exactly the per-command
    // read/write wait the socket timeout must bound.
    let url = format!("redis://{host}:{port}/");
    let client = redis::Client::open(url).unwrap();
    let mut ctrl = client.get_connection().unwrap();
    let _: () = redis::cmd("CLIENT")
        .arg("PAUSE")
        .arg(4000)
        .arg("WRITE")
        .query(&mut ctrl)
        .unwrap();

    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("pause.ndjson");
    let out_str = out.to_str().unwrap();

    // 2s phase, 500ms command timeout. Every command should hit the paused
    // server and time out well before the 4s pause ends.
    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"pause"},
      "phases":[{
        "id":"P","connections":1,"pipeline_depth":2,"warmup_requests":0,
        "commands":[{"command":"set","weight":1.0,"data_size_bytes":16}],
        "keyspace":{"keys_count":100,"key_size_bytes":16,"key_prefix":"p:","generation_alg":"sequential_int"},
        "completion":{"type":"duration","seconds":2}
      }]
    }"#;

    let driver = parse_driver_config_str(
        r#"{"schema_version":"1.0","driver_id":"redis-rs","mode":"standalone","command_timeout_ms":500}"#,
    )
    .unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new(host, port, driver, wl, out_str, None);

    let started = std::time::Instant::now();
    b.run().unwrap();
    let elapsed = started.elapsed();

    let rec = read_first(out_str);
    // The phase must finish near its 2s deadline (not run until the 4s pause
    // lifts) and must record errors from the timed-out commands.
    assert!(
        elapsed.as_secs_f64() < 3.5,
        "phase ran {elapsed:?}; command timeout did not bound the stall"
    );
    assert!(
        rec["totals"]["errors"].as_u64().unwrap() > 0,
        "a stalled server produced no timeout errors"
    );
}

#[test]
#[ignore = "requires a live Valkey server (VALKEY_HOST)"]
fn redis_rs_reports_resp2_metadata() {
    // redis 0.25 speaks RESP2 (no HELLO 3). The metadata must say so, rather than
    // mislabeling the row RESP3 like GLIDE.
    let (host, port) = match live_server() {
        Some(v) => v,
        None => return,
    };

    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("resp.ndjson");
    let out_str = out.to_str().unwrap();

    let workload = r#"{
      "schema_version":"1.0",
      "benchmark_profile":{"name":"resp"},
      "phases":[{
        "id":"P","connections":1,"warmup_requests":1,
        "commands":[{"command":"get","weight":1.0}],
        "keyspace":{"keys_count":10,"key_size_bytes":16,"key_prefix":"r:","generation_alg":"sequential_int"},
        "completion":{"type":"requests","requests":50}
      }]
    }"#;

    let driver = parse_driver_config_str(
        r#"{"schema_version":"1.0","driver_id":"redis-rs","mode":"standalone"}"#,
    )
    .unwrap();
    let wl = parse_workload_config_str(workload).unwrap();
    let mut b = Benchmark::new(host, port, driver, wl, out_str, None);
    b.run().unwrap();

    let rec = read_first(out_str);
    assert_eq!(
        rec["metadata"]["resp_protocol"].as_i64().unwrap(),
        2,
        "redis-rs must report RESP2"
    );
}
