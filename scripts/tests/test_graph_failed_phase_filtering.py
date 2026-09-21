"""A failed phase must not be averaged into a driver's reported throughput.

Engines emit a row for a phase that failed, so the failure stays visible in the
raw output. Those rows carry requests=0 and duration_ms=0, which aggregate_results
would otherwise fold in as a 0-RPS sample.
"""
import json

import pytest

# generate_graphs imports matplotlib at module scope (scripts/requirements.txt).
pytest.importorskip("matplotlib", reason="install scripts/requirements.txt")

from generate_graphs import AggregationKey, aggregate_results, load_results  # noqa: E402


def _row(driver, status, requests, duration_ms, connections=4):
    return {
        "metadata": {"driver_id": driver},
        "phase": {
            "id": "STEADY",
            "status": status,
            "duration_ms": duration_ms,
            "connections": connections,
        },
        "totals": {"requests": requests, "errors": 0 if status == "COMPLETED" else requests},
        "metrics": {},
    }


def _write(path, rows):
    path.write_text("".join(json.dumps(r) + "\n" for r in rows))
    return str(path)


def test_failed_phase_row_is_skipped(tmp_path):
    path = _write(
        tmp_path / "m.ndjson",
        [
            _row("redis-py", "COMPLETED", 50_000, 1000),
            _row("redis-py", "ERROR", 0, 0),
            _row("redis-py", "COMPLETED", 50_000, 1000),
        ],
    )
    results = load_results([path], "STEADY")
    assert len(results) == 2
    assert all(r["phase"]["status"] == "COMPLETED" for r in results)


def test_interrupted_phase_row_is_skipped(tmp_path):
    path = _write(tmp_path / "m.ndjson", [_row("redis-py", "INTERRUPTED", 12, 0)])
    assert load_results([path], "STEADY") == []


def test_row_without_status_is_kept(tmp_path):
    # Older engines omit `status`; absence must not silently drop real data.
    row = _row("jedis", "COMPLETED", 10_000, 500)
    del row["phase"]["status"]
    path = _write(tmp_path / "m.ndjson", [row])
    assert len(load_results([path], "STEADY")) == 1


def test_failed_row_does_not_drag_down_reported_rps(tmp_path):
    # Two good 50k-RPS runs plus one failed row averaged to ~33k before the fix.
    path = _write(
        tmp_path / "m.ndjson",
        [
            _row("redis-py", "COMPLETED", 50_000, 1000),
            _row("redis-py", "COMPLETED", 50_000, 1000),
            _row("redis-py", "ERROR", 0, 0),
        ],
    )
    key = AggregationKey(None, None, None)
    aggregated = aggregate_results(load_results([path], "STEADY"), key)
    assert len(aggregated) == 1
    agg = next(iter(aggregated.values()))
    assert agg["total_rps_values"] == [50_000.0, 50_000.0]
    assert abs(agg["total_rps_avg"] - 50_000.0) < 1
