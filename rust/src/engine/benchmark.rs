//! The benchmark engine.
//!
//! Concurrency model (matching the Java reference and the Python/Go engines):
//! **one client per connection**, driven by `pipeline_depth` worker threads that
//! share that connection. Raising `pipeline_depth` adds in-flight requests but
//! does not change which keys a connection issues, because the connection owns
//! its key generator and command selector, shared across its depth workers.
//!
//! A request-based phase target is a single atomic budget shared across all
//! workers, claimed one request at a time (like the Java reference's shared
//! `AtomicLong`), so a slow connection cannot cap the phase. Duration-based
//! phases use a wall-clock deadline.
//!
//! GLIDE multiplexes, so `pipeline_depth > 1` puts that many requests on the one
//! socket. A pooling driver would use up to `pipeline_depth` sockets (reported
//! via `sockets_per_client`).

use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use crate::client::{create_and_connect, BenchmarkClient, ClientError};
use crate::command::{create_all, Command};
use crate::config::{DriverConfig, PhaseConfig, WorkloadConfig};
use crate::metrics::{build_metadata, Metadata, MetricsCollector, NdjsonWriter};

use super::command_selector::CommandSelector;
use super::key_generator::{KeyGenerator, SharedCounter};
use super::rate_limiter::{acquire, RateLimiter};

// Back off a permanently-failing connection after this many consecutive
// failures, so it cannot spin at full CPU inflating the error count.
const CONSECUTIVE_FAILURE_BACKOFF_AFTER: u32 = 8;
const MAX_FAILURE_BACKOFF: Duration = Duration::from_millis(50);

const STATUS_COMPLETED: &str = "COMPLETED";
const STATUS_ERROR: &str = "ERROR";
const STATUS_INTERRUPTED: &str = "INTERRUPTED";

/// The benchmark orchestrator.
pub struct Benchmark {
    host: String,
    port: u16,
    driver_config: DriverConfig,
    workload_config: WorkloadConfig,
    writer: NdjsonWriter,
    commit_id: Option<String>,
    interrupted: Arc<AtomicBool>,
    had_error: bool,
}

impl Benchmark {
    pub fn new(
        host: impl Into<String>,
        port: u16,
        driver_config: DriverConfig,
        workload_config: WorkloadConfig,
        metrics_path: impl AsRef<std::path::Path>,
        commit_id: Option<String>,
    ) -> Self {
        Benchmark {
            host: host.into(),
            port,
            driver_config,
            workload_config,
            writer: NdjsonWriter::new(metrics_path),
            commit_id,
            interrupted: Arc::new(AtomicBool::new(false)),
            had_error: false,
        }
    }

    /// A flag the CLI can wire to SIGINT/SIGTERM for a graceful stop.
    pub fn interrupt_flag(&self) -> Arc<AtomicBool> {
        self.interrupted.clone()
    }

    /// True if any phase ended in a non-COMPLETED status.
    pub fn had_error(&self) -> bool {
        self.had_error
    }

    /// Run all phases in order.
    pub fn run(&mut self) -> Result<(), String> {
        log::info(&format!(
            "Starting benchmark: {} (driver={}, mode={})",
            self.workload_config.name(),
            self.driver_config.driver_id,
            self.driver_config.mode
        ));
        self.setup_metadata();

        let phases = self.workload_config.phases.clone();
        for phase in &phases {
            self.execute_phase(phase)?;
        }
        log::info("Benchmark completed");
        Ok(())
    }

    fn setup_metadata(&mut self) {
        let meta = match create_and_connect(&self.host, self.port, &self.driver_config, 1) {
            Ok(mut sample) => {
                let m =
                    build_metadata(&self.driver_config, self.commit_id.clone(), sample.as_ref());
                sample.close();
                m
            }
            Err(ClientError(e)) => {
                log::warn(&format!("Failed to get driver version for metadata: {e}"));
                Metadata {
                    commit_id: self.commit_id.clone(),
                    driver_id: Some(self.driver_config.driver_id.clone()),
                    primary_driver_version: Some("unknown".to_string()),
                    secondary_driver_id: self.driver_config.secondary_driver_id(),
                    secondary_driver_version: None,
                    driver_details: serde_json::Map::new(),
                }
            }
        };
        self.writer.set_metadata(meta);
    }

    fn execute_phase(&mut self, phase: &PhaseConfig) -> Result<(), String> {
        log::info(&format!(
            "=== Starting phase: {} ({}) ===",
            phase.id,
            phase.description()
        ));

        let depth = phase.effective_pipeline_depth();
        let mut collector = MetricsCollector::new();
        let mut status = STATUS_ERROR;
        let mut sockets_per_client = 1u32;

        match self.create_clients(phase) {
            Ok(clients) if !clients.is_empty() => {
                sockets_per_client = clients[0].sockets_per_client();
                match create_all(&phase.commands) {
                    Ok(commands) => {
                        let warmup_ok = if phase.warmup_requests > 0 {
                            self.warmup(&clients, phase.warmup_requests)
                        } else {
                            true
                        };

                        if warmup_ok {
                            collector.start();
                            status = self.run_workload(phase, &clients, &commands, &mut collector);
                            collector.stop();
                        } else {
                            log::error(&format!("phase {}: warmup failed", phase.id));
                        }
                    }
                    Err(e) => log::error(&format!("phase {}: {e}", phase.id)),
                }
                self.close_clients(clients);
            }
            Ok(_) => log::error(&format!("phase {}: no connections created", phase.id)),
            Err(ClientError(e)) => log::error(&format!(
                "phase {}: failed to create clients: {e}",
                phase.id
            )),
        }

        // A phase that failed before the workload started still needs real
        // timestamps: nulls would violate the schema and skew the graphs.
        if collector.start_millis().is_none() {
            collector.start();
            collector.stop();
        }

        if let Err(e) = self.writer.write_phase_results(
            &phase.id,
            status,
            phase.connections,
            &collector,
            depth,
            sockets_per_client,
        ) {
            log::error(&format!(
                "failed to write metrics for phase {}: {e}",
                phase.id
            ));
        }
        self.log_phase_summary(phase, &collector, status);

        if status != STATUS_COMPLETED {
            self.had_error = true;
        }
        Ok(())
    }

    fn create_clients(
        &self,
        phase: &PhaseConfig,
    ) -> Result<Vec<Box<dyn BenchmarkClient>>, ClientError> {
        let depth = phase.effective_pipeline_depth();
        let cps_limiter = RateLimiter::create(phase.cps_limit);
        log::info(&format!("Creating {} connections...", phase.connections));

        let mut clients = Vec::with_capacity(phase.connections as usize);
        for _ in 0..phase.connections {
            acquire(&cps_limiter);
            let client = create_and_connect(&self.host, self.port, &self.driver_config, depth)?;
            clients.push(client);
        }
        log::info(&format!("All {} connections established", clients.len()));
        Ok(clients)
    }

    /// Exactly `warmup_requests` PINGs per client (not multiplied by depth).
    /// Returns false on the first PING failure.
    fn warmup(&self, clients: &[Box<dyn BenchmarkClient>], warmup_requests: u32) -> bool {
        log::info(&format!("Warmup: {warmup_requests} PINGs per client..."));
        for client in clients {
            for _ in 0..warmup_requests {
                if !client.ping().success {
                    return false;
                }
            }
        }
        log::info("Warmup completed");
        true
    }

    fn run_workload(
        &self,
        phase: &PhaseConfig,
        clients: &[Box<dyn BenchmarkClient>],
        commands: &[Command],
        collector: &mut MetricsCollector,
    ) -> &'static str {
        let depth = phase.effective_pipeline_depth();
        let seed_base = phase.keyspace.seed_value();
        let shared_counter = SharedCounter::new();

        let duration_based = phase.completion.is_duration_based();
        let target_requests = phase.completion.total_requests() as i64;
        let deadline = Instant::now() + Duration::from_secs(phase.completion.duration_seconds());
        // Shared atomic budget: fetch_sub returns the value BEFORE decrement, so
        // a return of <= 0 means the budget is already exhausted (exact target).
        let remaining = Arc::new(AtomicI64::new(target_requests));
        let merged = Arc::new(Mutex::new(MetricsCollector::new()));
        let worker_panics = Arc::new(AtomicI64::new(0));
        let interrupted = &self.interrupted;

        log::info(&format!(
            "Starting {} worker threads ({} connections x pipeline_depth {})...",
            clients.len() as u32 * depth,
            clients.len(),
            depth
        ));

        thread::scope(|scope| {
            for (idx, client) in clients.iter().enumerate() {
                // One key generator + selector per CONNECTION, shared by its depth
                // workers. One rate limiter per connection, shared across its
                // depth slots, so the connection's rps share is not multiplied.
                let selector = Arc::new(CommandSelector::new(commands.to_vec()));
                let conn_rps = Arc::new(RateLimiter::create(phase.rps_limit));
                let client_ref: &dyn BenchmarkClient = client.as_ref();

                for _ in 0..depth {
                    let remaining = remaining.clone();
                    let conn_rps = conn_rps.clone();
                    let merged = merged.clone();
                    let worker_panics = worker_panics.clone();
                    let selector = selector.clone();
                    let counter = shared_counter.clone();
                    let interrupted = interrupted.clone();
                    let keyspace = &phase.keyspace;

                    scope.spawn(move || {
                        let mut key_gen =
                            KeyGenerator::with_seed(keyspace, seed_base + idx as i64, counter);
                        let outcome =
                            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                let mut local = MetricsCollector::new();
                                local.start();
                                Self::worker_loop(
                                    client_ref,
                                    &mut key_gen,
                                    &selector,
                                    &conn_rps,
                                    &remaining,
                                    &interrupted,
                                    duration_based,
                                    deadline,
                                    &mut local,
                                );
                                local.stop();
                                local
                            }));
                        match outcome {
                            Ok(local) => merged.lock().unwrap().merge_from(&local),
                            Err(_) => {
                                worker_panics.fetch_add(1, Ordering::Relaxed);
                            }
                        }
                    });
                }
            }
        });

        let merged = Arc::try_unwrap(merged)
            .ok()
            .expect("all workers joined")
            .into_inner()
            .unwrap();
        collector.merge_from(&merged);

        if self.interrupted.load(Ordering::Relaxed) {
            log::warn("Workload interrupted");
            return STATUS_INTERRUPTED;
        }
        if worker_panics.load(Ordering::Relaxed) > 0 {
            log::error("A worker thread panicked");
            return STATUS_ERROR;
        }
        // A phase where nothing succeeded produced no usable data.
        let successes = collector.total_requests - collector.total_errors;
        if collector.total_requests > 0 && successes == 0 {
            log::error(&format!(
                "All {} requests failed; reporting phase as ERROR",
                collector.total_requests
            ));
            return STATUS_ERROR;
        }
        log::info(&format!(
            "All operations completed ({} total requests)",
            collector.total_requests
        ));
        STATUS_COMPLETED
    }

    #[allow(clippy::too_many_arguments)]
    fn worker_loop(
        client: &dyn BenchmarkClient,
        key_gen: &mut KeyGenerator,
        selector: &CommandSelector,
        rps_limiter: &Option<RateLimiter>,
        remaining: &AtomicI64,
        interrupted: &AtomicBool,
        duration_based: bool,
        deadline: Instant,
        collector: &mut MetricsCollector,
    ) {
        let mut consecutive_failures: u32 = 0;
        loop {
            if interrupted.load(Ordering::Relaxed) {
                return;
            }
            if duration_based {
                if Instant::now() >= deadline {
                    return;
                }
            } else if remaining.fetch_sub(1, Ordering::Relaxed) <= 0 {
                return;
            }

            acquire(rps_limiter);
            let command = selector.select();
            let key = key_gen.next_key();
            let result = command.execute(client, &key);
            let success = result.success;
            collector.record(&result);

            if success {
                consecutive_failures = 0;
            } else {
                consecutive_failures += 1;
                if consecutive_failures >= CONSECUTIVE_FAILURE_BACKOFF_AFTER {
                    let backoff = Duration::from_millis(
                        (consecutive_failures - CONSECUTIVE_FAILURE_BACKOFF_AFTER + 1) as u64,
                    )
                    .min(MAX_FAILURE_BACKOFF);
                    thread::sleep(backoff);
                }
            }
        }
    }

    fn close_clients(&self, mut clients: Vec<Box<dyn BenchmarkClient>>) {
        log::info(&format!("Closing {} connections...", clients.len()));
        for client in clients.iter_mut() {
            client.close();
        }
    }

    fn log_phase_summary(&self, phase: &PhaseConfig, collector: &MetricsCollector, status: &str) {
        let duration_s = collector.duration_millis() as f64 / 1000.0;
        let total = collector.total_requests;
        let rps = if duration_s > 0.0 {
            (total as f64 / duration_s).round() as u64
        } else {
            0
        };
        log::info(&format!(
            "=== Phase {} completed: {} === Duration: {:.1}s | Requests: {} | Errors: {} | RPS: {} | connections={} x depth={}",
            phase.id,
            status,
            duration_s,
            total,
            collector.total_errors,
            rps,
            phase.connections,
            phase.effective_pipeline_depth(),
        ));
    }
}

// Minimal leveled logging to stderr, so the engine's progress is visible without
// pulling in a logging framework.
mod log {
    pub fn info(msg: &str) {
        eprintln!("INFO  {msg}");
    }
    pub fn warn(msg: &str) {
        eprintln!("WARN  {msg}");
    }
    pub fn error(msg: &str) {
        eprintln!("ERROR {msg}");
    }
}
