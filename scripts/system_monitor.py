"""
System Monitor — Thread-based system metrics collector for benchmark orchestration.

Collects CPU usage (system-wide via /proc/stat), memory RSS (of target process group
via /proc/<pid>/status), and system-wide memory availability (via /proc/meminfo).
Writes NDJSON samples to <label>.system.ndjson.

Designed to run as a lightweight daemon thread inside run_benchmark_matrix.py,
eliminating the need for a separate subprocess. All /proc reads are essentially
zero-cost kernel operations — no measurable impact on benchmark traffic generation.

Output format (one JSON line per sample):
    {
        "timestamp_iso": "2026-03-09T20:01:30.500Z",
        "timestamp_epoch": 1741550490.5,
        "cpu_percent": 45.2,
        "cpu_cores": 96,
        "memory_rss_bytes": 2147483648,
        "memory_available_bytes": 64424509440,
        "memory_total_bytes": 137438953472
    }

Usage:
    # As a context manager within run_benchmark_matrix.py:
    with SystemMonitor(output_path, interval=0.5, target_pgid=pgid) as monitor:
        subprocess.run([...])  # benchmark runs here

    # Standalone (for testing):
    monitor = SystemMonitor("/tmp/test.system.ndjson", interval=0.5)
    monitor.start()
    time.sleep(5)
    monitor.stop()
"""

import json
import os
import threading
import time
from datetime import datetime, timezone
from pathlib import Path


# ═══════════════════════════════════════════════════════════════════════════════
# /proc Readers — Minimal-overhead kernel reads
# ═══════════════════════════════════════════════════════════════════════════════

def read_cpu_jiffies():
    """Read aggregate CPU jiffies from /proc/stat.

    Returns (total_jiffies, idle_jiffies).
    The 'cpu' line format: cpu user nice system idle iowait irq softirq steal [guest guest_nice]
    """
    with open("/proc/stat") as f:
        for line in f:
            if line.startswith("cpu "):
                parts = line.split()
                values = [int(x) for x in parts[1:]]
                total = sum(values)
                idle = values[3] + values[4]  # idle + iowait
                return total, idle
    raise RuntimeError("Could not read /proc/stat cpu line")


def get_cpu_count():
    """Return the number of CPU cores."""
    try:
        return os.cpu_count() or 1
    except Exception:
        return 1


def read_process_rss(pid):
    """Read RSS (Resident Set Size) from /proc/<pid>/status.

    Returns RSS in bytes, or None if the process doesn't exist.
    """
    try:
        with open(f"/proc/{pid}/status") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    # Format: "VmRSS:    123456 kB"
                    return int(line.split()[1]) * 1024  # kB → bytes
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        return None
    return None


def read_process_group_rss(pgid):
    """Sum RSS of all processes in a process group.

    Walks /proc/<pid>/stat to find processes matching the given PGID,
    then sums their RSS from /proc/<pid>/status.

    Returns total RSS in bytes, or None if no processes found.
    """
    if pgid is None:
        return None

    total_rss = 0
    found = False

    try:
        for entry in os.listdir("/proc"):
            if not entry.isdigit():
                continue
            pid = int(entry)
            try:
                with open(f"/proc/{pid}/stat") as f:
                    stat_line = f.read()
                # PGID is the 5th field (after pid, comm, state, ppid)
                # comm may contain spaces and parens, so find closing paren first
                close_paren = stat_line.rfind(")")
                if close_paren == -1:
                    continue
                fields = stat_line[close_paren + 2:].split()
                # fields[0]=state, fields[1]=ppid, fields[2]=pgrp
                proc_pgid = int(fields[2])
                if proc_pgid == pgid:
                    rss = read_process_rss(pid)
                    if rss is not None:
                        total_rss += rss
                        found = True
            except (FileNotFoundError, ProcessLookupError, PermissionError,
                    ValueError, IndexError):
                continue
    except (FileNotFoundError, PermissionError):
        return None

    return total_rss if found else None


def read_system_memory():
    """Read MemTotal and MemAvailable from /proc/meminfo.

    Returns (total_bytes, available_bytes) or (None, None) on error.
    """
    total = None
    available = None
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    total = int(line.split()[1]) * 1024  # kB → bytes
                elif line.startswith("MemAvailable:"):
                    available = int(line.split()[1]) * 1024
                if total is not None and available is not None:
                    break
    except (FileNotFoundError, PermissionError):
        pass
    return total, available


# ═══════════════════════════════════════════════════════════════════════════════
# SystemMonitor — Thread-based collector
# ═══════════════════════════════════════════════════════════════════════════════

class SystemMonitor:
    """Lightweight system metrics collector running in a background daemon thread.

    Collects CPU% (system-wide), memory RSS (of target process group),
    and system memory availability at regular intervals.
    Writes NDJSON samples to the specified output file.

    Args:
        output_path: Path to the .system.ndjson output file (append mode).
        interval: Sampling interval in seconds (default: 0.5).
        target_pgid: Process group ID to track for memory RSS.
            If None, memory_rss_bytes will be null in output.

    Usage:
        with SystemMonitor("output.system.ndjson", interval=0.5, target_pgid=pgid):
            subprocess.run([...])
    """

    def __init__(self, output_path, interval=0.5, target_pgid=None):
        self.output_path = Path(output_path)
        self.interval = interval
        self.target_pgid = target_pgid
        self._stop_event = threading.Event()
        self._thread = None
        self._cpu_cores = get_cpu_count()

    def start(self):
        """Start the background monitoring thread."""
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, daemon=True, name="system-monitor")
        self._thread.start()

    def stop(self):
        """Signal the thread to stop and wait for it to finish."""
        self._stop_event.set()
        if self._thread is not None and self._thread.is_alive():
            self._thread.join(timeout=5)

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.stop()
        return False

    def _run(self):
        """Main monitoring loop — runs in daemon thread."""
        # Ensure output directory exists
        self.output_path.parent.mkdir(parents=True, exist_ok=True)

        try:
            prev_total, prev_idle = read_cpu_jiffies()
        except Exception:
            prev_total, prev_idle = 0, 0

        # Wait one interval before first sample (need two readings for delta)
        self._stop_event.wait(self.interval)

        try:
            with open(self.output_path, "a") as outfile:
                while not self._stop_event.is_set():
                    try:
                        record, new_total, new_idle = self._collect_sample(prev_total, prev_idle)
                        outfile.write(json.dumps(record) + "\n")
                        outfile.flush()

                        # Update CPU baseline for next delta
                        prev_total = new_total
                        prev_idle = new_idle
                    except Exception:
                        pass  # Silently skip failed samples

                    self._stop_event.wait(self.interval)
        except Exception:
            pass  # File write errors — nothing we can do

    def _collect_sample(self, prev_total, prev_idle):
        """Collect one sample of all metrics."""
        now = time.time()
        ts_iso = datetime.fromtimestamp(now, tz=timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"

        # CPU
        try:
            now_total, now_idle = read_cpu_jiffies()
            delta_total = now_total - prev_total
            delta_idle = now_idle - prev_idle
            cpu_percent = round((1.0 - delta_idle / delta_total) * 100.0, 2) \
                if delta_total > 0 else 0.0
        except Exception:
            now_total, now_idle = prev_total, prev_idle
            cpu_percent = 0.0

        # Memory — process group RSS
        memory_rss = read_process_group_rss(self.target_pgid)

        # Memory — system-wide
        mem_total, mem_available = read_system_memory()

        record = {
            "timestamp_iso": ts_iso,
            "timestamp_epoch": round(now, 3),
            "cpu_percent": cpu_percent,
            "cpu_cores": self._cpu_cores,
            "memory_rss_bytes": memory_rss,
            "memory_available_bytes": mem_available,
            "memory_total_bytes": mem_total,
        }

        return record, now_total, now_idle
