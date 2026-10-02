//! CLI entry point for the resp-bench Rust engine.
//!
//! Implements the shared cross-engine CLI contract: `--server`, `--driver`,
//! `--workload`, `--metrics`, plus `--info`, `--commit-id`, `--version`.

use std::process::ExitCode;
use std::sync::atomic::Ordering;

use clap::Parser;

use resp_bench::client::supported_drivers;
use resp_bench::command::supported_commands;
use resp_bench::config::{load_driver_config, load_workload_config};
use resp_bench::engine::Benchmark;

#[derive(Parser, Debug)]
#[command(name = "resp-bench", version, about = "resp-bench Rust engine")]
struct Cli {
    /// Server address HOST:PORT.
    #[arg(long, default_value = "localhost:6379")]
    server: String,

    /// Driver configuration file.
    #[arg(long)]
    driver: Option<String>,

    /// Workload configuration file.
    #[arg(long)]
    workload: Option<String>,

    /// Metrics output file (NDJSON).
    #[arg(long)]
    metrics: Option<String>,

    /// Git commit ID for metadata.
    #[arg(long = "commit-id")]
    commit_id: Option<String>,

    /// Show supported drivers and commands, then exit.
    #[arg(long)]
    info: bool,
}

fn parse_server(server: &str) -> (String, u16) {
    match server.split_once(':') {
        Some((host, port)) => {
            let host = if host.is_empty() { "localhost" } else { host };
            (host.to_string(), port.parse().unwrap_or(6379))
        }
        None => (server.to_string(), 6379),
    }
}

fn print_info() {
    println!("resp-bench Rust Engine v{}", env!("CARGO_PKG_VERSION"));
    println!();
    println!("Supported Drivers:");
    for driver in supported_drivers() {
        println!("  - {driver}");
    }
    println!();
    println!("Supported Commands:");
    for command in supported_commands() {
        println!("  - {command}");
    }
    println!();
    println!(
        "Concurrency: threads, one client per connection, pipeline_depth workers per connection"
    );
}

fn run() -> Result<bool, String> {
    let cli = Cli::parse();

    if cli.info {
        print_info();
        return Ok(false);
    }

    let driver_path = cli
        .driver
        .as_deref()
        .ok_or("Missing required option: --driver")?;
    let workload_path = cli
        .workload
        .as_deref()
        .ok_or("Missing required option: --workload")?;
    let metrics_path = cli
        .metrics
        .as_deref()
        .ok_or("Missing required option: --metrics")?;

    let driver_config =
        load_driver_config(driver_path).map_err(|e| format!("driver config: {e}"))?;
    let workload_config =
        load_workload_config(workload_path).map_err(|e| format!("workload config: {e}"))?;

    let (host, port) = parse_server(&cli.server);

    let mut benchmark = Benchmark::new(
        host,
        port,
        driver_config,
        workload_config,
        metrics_path,
        cli.commit_id.clone(),
    );

    // Wire SIGINT/SIGTERM to a graceful interrupt via a shared flag. We install a
    // small handler that flips the flag; the engine's workers poll it.
    install_signal_handler(benchmark.interrupt_flag());

    benchmark.run()?;
    Ok(benchmark.had_error())
}

#[cfg(unix)]
fn install_signal_handler(flag: std::sync::Arc<std::sync::atomic::AtomicBool>) {
    use std::sync::atomic::AtomicBool;
    use std::sync::Arc;

    static INSTALLED: AtomicBool = AtomicBool::new(false);
    if INSTALLED.swap(true, Ordering::SeqCst) {
        return;
    }

    static FLAG_PTR: std::sync::OnceLock<Arc<AtomicBool>> = std::sync::OnceLock::new();
    let _ = FLAG_PTR.set(flag);

    // The handler only stores into a static atomic, which is async-signal-safe.
    extern "C" fn handle(_sig: libc::c_int) {
        if let Some(f) = FLAG_PTR.get() {
            f.store(true, Ordering::SeqCst);
        }
    }

    unsafe {
        libc::signal(libc::SIGINT, handle as *const () as libc::sighandler_t);
        libc::signal(libc::SIGTERM, handle as *const () as libc::sighandler_t);
    }
}

#[cfg(not(unix))]
fn install_signal_handler(_flag: std::sync::Arc<std::sync::atomic::AtomicBool>) {}

fn main() -> ExitCode {
    match run() {
        Ok(false) => ExitCode::SUCCESS,
        Ok(true) => {
            eprintln!("Error: one or more phases ended with status ERROR");
            ExitCode::FAILURE
        }
        Err(e) => {
            eprintln!("Error: {e}");
            ExitCode::FAILURE
        }
    }
}
