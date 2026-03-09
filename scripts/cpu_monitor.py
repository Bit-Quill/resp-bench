#!/usr/bin/env python3
"""
CPU Monitor — Samples /proc/stat at regular intervals and writes NDJSON.

Reads the aggregate CPU line from /proc/stat, computes delta CPU% between
samples, and appends one JSON line per sample to the output file.

Each line:
    {"timestamp_iso": "2026-03-09T20:01:30.500Z", "timestamp_epoch": 1741550490.5,
     "cpu_percent": 45.2, "cpu_cores": 96}

Runs until killed (SIGTERM/SIGINT). Designed to be started as a background
process by run_benchmark_all.py.

Usage:
    python scripts/cpu_monitor.py --output path/to/driver.cpu.ndjson [--interval 0.5]
"""

import argparse
import json
import os
import signal
import sys
import time
from datetime import datetime, timezone


def read_cpu_jiffies():
    """Read aggregate CPU jiffies from /proc/stat.

    Returns (total_jiffies, idle_jiffies).
    The 'cpu' line format: cpu user nice system idle iowait irq softirq steal [guest guest_nice]
    """
    with open("/proc/stat") as f:
        for line in f:
            if line.startswith("cpu "):
                parts = line.split()
                # parts[0] = 'cpu', parts[1:] = jiffies per state
                values = [int(x) for x in parts[1:]]
                total = sum(values)
                # idle = idle + iowait (indices 3,4 in the values list)
                idle = values[3] + values[4]
                return total, idle
    raise RuntimeError("Could not read /proc/stat cpu line")


def get_cpu_count():
    """Return the number of CPU cores."""
    try:
        return os.cpu_count() or 1
    except Exception:
        return 1


def main():
    parser = argparse.ArgumentParser(description="CPU usage monitor writing NDJSON")
    parser.add_argument("--output", "-o", required=True, help="Output NDJSON file path (append mode)")
    parser.add_argument("--interval", "-i", type=float, default=0.5, help="Sampling interval in seconds (default: 0.5)")
    args = parser.parse_args()

    interval = args.interval
    cpu_cores = get_cpu_count()

    # Graceful shutdown
    running = True

    def handle_signal(signum, frame):
        nonlocal running
        running = False

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    # Open file in append mode
    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    outfile = open(args.output, "a")

    try:
        prev_total, prev_idle = read_cpu_jiffies()
        time.sleep(interval)

        while running:
            now_total, now_idle = read_cpu_jiffies()
            delta_total = now_total - prev_total
            delta_idle = now_idle - prev_idle

            if delta_total > 0:
                cpu_percent = round((1.0 - delta_idle / delta_total) * 100.0, 2)
            else:
                cpu_percent = 0.0

            now = time.time()
            ts_iso = datetime.fromtimestamp(now, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"

            record = {
                "timestamp_iso": ts_iso,
                "timestamp_epoch": round(now, 3),
                "cpu_percent": cpu_percent,
                "cpu_cores": cpu_cores,
            }
            outfile.write(json.dumps(record) + "\n")
            outfile.flush()

            prev_total, prev_idle = now_total, now_idle
            time.sleep(interval)

    except Exception as e:
        print(f"cpu_monitor: error: {e}", file=sys.stderr)
    finally:
        outfile.close()


if __name__ == "__main__":
    main()
