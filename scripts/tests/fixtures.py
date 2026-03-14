"""Synthetic NDJSON fixture generators for testing graph and matrix scripts."""
import json
import time
from datetime import datetime, timezone
from pathlib import Path


def make_steady_record(connections=1, total_requests=10000, duration_ms=10000,
                       get_requests=None, set_requests=None,
                       get_p50=100, get_p95=200, get_p99=500, get_p999=1000,
                       set_p50=120, set_p95=250, set_p99=550, set_p999=1100,
                       driver_id=None, primary_version=None,
                       secondary_driver_id=None, secondary_version=None,
                       start_epoch=None):
    """Generate a synthetic STEADY phase NDJSON record dict.

    If get_requests/set_requests not given, splits total_requests 50/50.
    """
    if get_requests is None:
        get_requests = total_requests // 2
    if set_requests is None:
        set_requests = total_requests - get_requests

    if start_epoch is None:
        start_epoch = time.time()
    end_epoch = start_epoch + duration_ms / 1000.0

    start_ts = datetime.fromtimestamp(start_epoch, tz=timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
    finish_ts = datetime.fromtimestamp(end_epoch, tz=timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"

    record = {
        "phase": {
            "id": "STEADY",
            "status": "COMPLETED",
            "start_timestamp": start_ts,
            "finish_timestamp": finish_ts,
            "duration_ms": duration_ms,
            "connections": connections,
        },
        "totals": {
            "requests": total_requests,
            "errors": 0,
        },
        "metrics": {
            "GET": {
                "requests": get_requests,
                "errors": 0,
                "latency": {
                    "unit": "us",
                    "count": get_requests,
                    "summary": {
                        "min": max(1, get_p50 - 50),
                        "p50": get_p50,
                        "p95": get_p95,
                        "p99": get_p99,
                        "p999": get_p999,
                        "max": get_p999 + 500,
                    },
                },
            },
            "SET": {
                "requests": set_requests,
                "errors": 0,
                "latency": {
                    "unit": "us",
                    "count": set_requests,
                    "summary": {
                        "min": max(1, set_p50 - 50),
                        "p50": set_p50,
                        "p95": set_p95,
                        "p99": set_p99,
                        "p999": set_p999,
                        "max": set_p999 + 500,
                    },
                },
            },
        },
    }

    if driver_id or primary_version:
        record["metadata"] = {}
        if driver_id:
            record["metadata"]["driver_id"] = driver_id
        if primary_version:
            record["metadata"]["primary_driver_version"] = primary_version
        if secondary_driver_id:
            record["metadata"]["secondary_driver_id"] = secondary_driver_id
        if secondary_version:
            record["metadata"]["secondary_driver_version"] = secondary_version

    return record


def make_cpu_sample(epoch, cpu_percent):
    """Generate a synthetic CPU NDJSON record dict."""
    ts_iso = datetime.fromtimestamp(epoch, tz=timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
    return {
        "timestamp_iso": ts_iso,
        "timestamp_epoch": round(epoch, 3),
        "cpu_percent": cpu_percent,
        "cpu_cores": 4,
    }


def write_ndjson(path, records):
    """Write a list of record dicts as NDJSON lines to a file."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w") as f:
        for rec in records:
            f.write(json.dumps(rec) + "\n")


def write_legacy_fixtures(tmpdir, drivers, client_counts, iterations=3,
                          base_rps=10000, rps_per_client=1000):
    """Create a full legacy layout directory tree with synthetic data.

    Returns the results directory path.
    """
    results_dir = Path(tmpdir) / "results"
    base_epoch = 1700000000.0

    for cc in client_counts:
        cc_dir = results_dir / f"{cc}-clients"
        cc_dir.mkdir(parents=True, exist_ok=True)

        for driver in drivers:
            records = []
            for i in range(iterations):
                rps = base_rps + cc * rps_per_client
                total_req = int(rps * 10)  # 10 second duration
                start = base_epoch + i * 15 + cc * 100
                records.append(make_steady_record(
                    connections=cc,
                    total_requests=total_req,
                    duration_ms=10000,
                    get_p50=100 + cc,
                    set_p50=120 + cc,
                    driver_id=driver,
                    primary_version="1.0.0",
                    start_epoch=start,
                ))
            write_ndjson(cc_dir / f"{driver}.ndjson", records)

    return results_dir


def write_flat_fixtures(tmpdir, series_configs, client_counts, iterations=3,
                        manifest=None):
    """Create a flat layout directory with synthetic data.

    series_configs: list of dicts with keys:
        - label: str (filename stem)
        - base_rps: float (base RPS at 1 client)
        - rps_per_client: float (additional RPS per client)
        - driver_id: str (optional)
        - primary_version: str (optional)

    Returns the results directory path.
    """
    results_dir = Path(tmpdir) / "results"
    results_dir.mkdir(parents=True, exist_ok=True)
    base_epoch = 1700000000.0

    for sc in series_configs:
        label = sc["label"]
        records = []
        for cc in client_counts:
            for i in range(iterations):
                rps = sc.get("base_rps", 10000) + cc * sc.get("rps_per_client", 1000)
                total_req = int(rps * 10)
                start = base_epoch + i * 15 + cc * 100
                records.append(make_steady_record(
                    connections=cc,
                    total_requests=total_req,
                    duration_ms=10000,
                    get_p50=sc.get("get_p50", 100),
                    set_p50=sc.get("set_p50", 120),
                    driver_id=sc.get("driver_id"),
                    primary_version=sc.get("primary_version"),
                    start_epoch=start,
                ))
        write_ndjson(results_dir / f"{label}.ndjson", records)

    if manifest:
        (results_dir / "_manifest.json").write_text(json.dumps(manifest, indent=2))

    return results_dir


def write_flat_with_cpu(tmpdir, series_configs, client_counts, iterations=3,
                        cpu_percent=50.0):
    """Create flat layout with CPU data files."""
    results_dir = write_flat_fixtures(tmpdir, series_configs, client_counts, iterations)
    base_epoch = 1700000000.0

    for sc in series_configs:
        label = sc["label"]
        cpu_samples = []
        for cc in client_counts:
            for i in range(iterations):
                start = base_epoch + i * 15 + cc * 100
                # Create CPU samples within the STEADY window
                for s in range(20):
                    cpu_samples.append(make_cpu_sample(
                        start + s * 0.5,
                        cpu_percent + cc * 0.5,
                    ))
        write_ndjson(results_dir / f"{label}.system.ndjson", cpu_samples)

    return results_dir
