#!/usr/bin/env python3
"""
run_benchmark_all.py — Run all Java benchmarks with CPU monitoring.

Replaces the former run_benchmark_all.sh bash script with a Python
implementation that also starts a cpu_monitor.py background process for
each benchmark run to record client-side CPU usage.

Generates workload files for each client count from the single-client
template, then runs every driver × client-count combination N times,
flushing the server between runs. Results are appended to NDJSON files:
    <output-dir>/<N>-clients/<driver>.ndjson
    <output-dir>/<N>-clients/<driver>.cpu.ndjson  (CPU time-series)

Driver profiles:
    default         — Framework-recommended defaults (no pooling, no tuning)
    high-throughput — Tuned for maximum RPS (pooling, share_native_connection=false, etc.)

Prerequisites:
    - pwd is the repo root
    - Valkey/Redis server running at the given host on port 6379
    - Java 21+, Maven, redis-cli in PATH
    - Python 3.8+

Usage:
    python scripts/run_benchmark_all.py --profile <default|high-throughput> \\
        --output-dir results/m5.metal/run1 --server-host 10.0.0.5

Examples:
    python scripts/run_benchmark_all.py --profile high-throughput \\
        --output-dir results/m5.metal/high-throughput --server-host localhost

    python scripts/run_benchmark_all.py --profile default \\
        --output-dir results/m5.metal/default --server-host 192.168.1.50
"""

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

# ═══════════════════════════════════════════════════════════════════════════════
# Configuration
# ═══════════════════════════════════════════════════════════════════════════════

ITER_CNT = 10
CLIENT_COUNTS = [1, 2, 4, 8, 16, 32, 64, 128]

DRIVERS = [
    "jedis",
    "valkey-glide",
    "lettuce",
    "redisson",
    "spring-data-redis-jedis",
    "spring-data-redis-lettuce",
    "spring-data-valkey-glide",
    "spring-data-valkey-jedis",
    "spring-data-valkey-lettuce",
]

# Drivers that have a pool_size config that should match the client count
POOLED_DRIVERS = {
    "spring-data-valkey-jedis",
    "spring-data-valkey-glide",
    "spring-data-redis-jedis",
    "spring-data-valkey-lettuce",
    "spring-data-redis-lettuce",
}

TEMPLATE_WORKLOAD = Path("configs/workloads/reference/basic-standalone-single-client.json")
WORKLOAD_DIR = Path("configs/workloads/reference")

CPU_MONITOR_SCRIPT = Path("scripts/cpu_monitor.py")
CPU_MONITOR_INTERVAL = 0.5  # seconds

VALID_PROFILES = {"default", "high-throughput"}


# ═══════════════════════════════════════════════════════════════════════════════
# Workload Generation
# ═══════════════════════════════════════════════════════════════════════════════

def generate_workload_files():
    """Generate workload JSON files for each client count from the single-client template."""
    template = json.loads(TEMPLATE_WORKLOAD.read_text())

    for client_cnt in CLIENT_COUNTS:
        workload = json.loads(json.dumps(template))  # deep copy
        for phase in workload.get("phases", []):
            phase["connections"] = client_cnt

        out_path = WORKLOAD_DIR / f"basic-standalone-{client_cnt}-clients.json"
        out_path.write_text(json.dumps(workload, indent=4) + "\n")
        print(f"  Generated workload: {out_path}")


# ═══════════════════════════════════════════════════════════════════════════════
# Driver Config Generation (pool_size = client_count for pooled drivers)
# ═══════════════════════════════════════════════════════════════════════════════

def generate_pooled_driver_configs(driver_dir: Path, tmpdir: Path):
    """Generate per-client-count driver configs for pooled drivers.

    For each (client_count, pooled_driver), copies the driver config and
    sets pool_size = client_count. Output goes to tmpdir/<client_cnt>/<driver>.json.
    """
    for client_cnt in CLIENT_COUNTS:
        for driver in POOLED_DRIVERS:
            src = driver_dir / f"{driver}.json"
            if not src.exists():
                continue

            config = json.loads(src.read_text())
            sdc = config.get("specific_driver_config", {})
            sdc["pool_size"] = client_cnt
            config["specific_driver_config"] = sdc

            out_dir = tmpdir / str(client_cnt)
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / f"{driver}.json"
            out_path.write_text(json.dumps(config, indent=4) + "\n")


# ═══════════════════════════════════════════════════════════════════════════════
# CPU Monitor Management
# ═══════════════════════════════════════════════════════════════════════════════

def start_cpu_monitor(output_path: str) -> subprocess.Popen:
    """Start cpu_monitor.py as a background process."""
    proc = subprocess.Popen(
        [
            sys.executable,
            str(CPU_MONITOR_SCRIPT),
            "--output", output_path,
            "--interval", str(CPU_MONITOR_INTERVAL),
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    return proc


def stop_cpu_monitor(proc: subprocess.Popen):
    """Stop the cpu_monitor.py background process gracefully."""
    if proc.poll() is not None:
        return  # already exited
    proc.send_signal(signal.SIGTERM)
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


# ═══════════════════════════════════════════════════════════════════════════════
# Benchmark Execution
# ═══════════════════════════════════════════════════════════════════════════════

def flush_server(server_host: str, port: int):
    """Run redis-cli FLUSHALL on the server."""
    subprocess.run(
        ["redis-cli", "-h", server_host, "-p", str(port), "flushall"],
        check=True,
        capture_output=True,
    )


def run_benchmark(server: str, driver_file: str, workload_file: str, metrics_output: str):
    """Run a single benchmark via `make java-run`."""
    result = subprocess.run(
        [
            "make", "java-run",
            f"SERVER={server}",
            f"DRIVER={driver_file}",
            f"WORKLOAD={workload_file}",
            f"METRICS_OUTPUT={metrics_output}",
        ],
        check=True,
    )


def run_all_benchmarks(profile: str, output_dir: Path, server_host: str, port: int = 6379):
    """Main benchmark loop: iterations × client_counts × drivers."""
    driver_dir = Path(f"configs/drivers/{profile}")
    if not driver_dir.is_dir():
        print(f"Error: driver config directory not found: {driver_dir}", file=sys.stderr)
        sys.exit(1)

    server = f"{server_host}:{port}"

    print("=" * 70)
    print(f"Profile:     {profile}")
    print(f"Driver dir:  {driver_dir}")
    print(f"Output dir:  {output_dir}")
    print(f"Server:      {server}")
    print(f"Iterations:  {ITER_CNT}")
    print(f"Client counts: {CLIENT_COUNTS}")
    print(f"Drivers:     {DRIVERS}")
    print(f"CPU monitor: interval={CPU_MONITOR_INTERVAL}s")
    print("=" * 70)

    # Generate workload files
    print("\nGenerating workload files...")
    generate_workload_files()

    # Generate pooled driver configs in a temp directory
    tmpdir = Path(tempfile.mkdtemp(prefix="resp-bench-drivers-"))
    try:
        print(f"Generating pooled driver configs in {tmpdir}...")
        generate_pooled_driver_configs(driver_dir, tmpdir)

        # Main benchmark loop
        for iteration in range(1, ITER_CNT + 1):
            for client_cnt in CLIENT_COUNTS:
                workload_file = str(WORKLOAD_DIR / f"basic-standalone-{client_cnt}-clients.json")

                for driver in DRIVERS:
                    print(f"\n=== iter={iteration}  clients={client_cnt}  "
                          f"driver={driver}  profile={profile} ===")

                    # Flush server
                    flush_server(server_host, port)

                    # Determine output paths
                    run_dir = output_dir / f"{client_cnt}-clients"
                    run_dir.mkdir(parents=True, exist_ok=True)
                    metrics_output = str(run_dir / f"{driver}.ndjson")
                    cpu_output = str(run_dir / f"{driver}.cpu.ndjson")

                    # Determine driver config file
                    pooled_config = tmpdir / str(client_cnt) / f"{driver}.json"
                    if pooled_config.exists():
                        driver_file = str(pooled_config)
                    else:
                        driver_file = str(driver_dir / f"{driver}.json")

                    # Start CPU monitor
                    cpu_proc = start_cpu_monitor(cpu_output)

                    try:
                        # Run benchmark
                        run_benchmark(server, driver_file, workload_file, metrics_output)
                    except subprocess.CalledProcessError as e:
                        print(f"ERROR: Benchmark failed for {driver} with {client_cnt} clients "
                              f"(iter {iteration}): {e}", file=sys.stderr)
                    finally:
                        # Stop CPU monitor
                        stop_cpu_monitor(cpu_proc)

    finally:
        # Clean up temp directory
        shutil.rmtree(tmpdir, ignore_errors=True)

    print("\n" + "=" * 70)
    print("All benchmarks completed!")
    print(f"Results in: {output_dir}")
    print("=" * 70)


# ═══════════════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════════════

def parse_args():
    parser = argparse.ArgumentParser(
        description="Run all Java benchmarks with CPU monitoring",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python scripts/run_benchmark_all.py --profile high-throughput \\
      --output-dir results/m5.metal/high-throughput --server-host localhost

  python scripts/run_benchmark_all.py --profile default \\
      --output-dir results/m5.metal/default --server-host 192.168.1.50
""",
    )
    parser.add_argument(
        "--profile",
        required=True,
        choices=sorted(VALID_PROFILES),
        help="Driver configuration profile to use",
    )
    parser.add_argument(
        "--output-dir",
        required=True,
        help="Directory to write benchmark results",
    )
    parser.add_argument(
        "--server-host",
        default="localhost",
        help="Hostname/IP of the Valkey/Redis server (default: localhost)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=6379,
        help="Port of the Valkey/Redis server (default: 6379)",
    )
    parser.add_argument(
        "--iterations",
        type=int,
        default=ITER_CNT,
        help=f"Number of iterations per driver/client-count (default: {ITER_CNT})",
    )
    parser.add_argument(
        "--client-counts",
        type=str,
        default=None,
        help="Comma-separated list of client counts (default: 1,2,4,8,16,32,64,128)",
    )
    parser.add_argument(
        "--drivers",
        type=str,
        default=None,
        help="Comma-separated list of drivers to run (default: all)",
    )
    parser.add_argument(
        "--cpu-interval",
        type=float,
        default=CPU_MONITOR_INTERVAL,
        help=f"CPU monitor sampling interval in seconds (default: {CPU_MONITOR_INTERVAL})",
    )
    return parser.parse_args()


def main():
    args = parse_args()

    global ITER_CNT, CLIENT_COUNTS, DRIVERS, CPU_MONITOR_INTERVAL

    ITER_CNT = args.iterations

    if args.client_counts:
        CLIENT_COUNTS = [int(x.strip()) for x in args.client_counts.split(",")]

    if args.drivers:
        DRIVERS = [x.strip() for x in args.drivers.split(",")]

    CPU_MONITOR_INTERVAL = args.cpu_interval

    output_dir = Path(args.output_dir)

    run_all_benchmarks(args.profile, output_dir, args.server_host, args.port)


if __name__ == "__main__":
    main()
