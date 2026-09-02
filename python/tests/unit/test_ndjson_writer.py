import json

from resp_bench.command.command import CommandResult
from resp_bench.metrics.collector import MetricsCollector
from resp_bench.metrics.ndjson_writer import NdjsonWriter


def _collector_with_data():
    collector = MetricsCollector()
    collector.start()
    for latency in (100, 200, 300, 400, 500):
        collector.record(CommandResult(command_name="GET", latency_micros=latency, success=True))
    collector.record(CommandResult(command_name="SET", latency_micros=1000, success=True))
    collector.record(CommandResult(command_name="SET", latency_micros=0, success=False))
    collector.stop()
    return collector


def test_ndjson_schema(tmp_path):
    out = tmp_path / "metrics.ndjson"
    writer = NdjsonWriter(str(out))
    writer.set_metadata(
        commit_id="abc123",
        driver_id="redis-py",
        primary_driver_version="8.1.0",
    )
    writer.write_phase_results(
        phase_id="STEADY",
        status="COMPLETED",
        connections=4,
        collector=_collector_with_data(),
    )

    lines = out.read_text().splitlines()
    assert len(lines) == 1
    obj = json.loads(lines[0])

    assert obj["metadata"]["driver_id"] == "redis-py"
    assert obj["metadata"]["commit_id"] == "abc123"
    assert obj["metadata"]["timestamp"].endswith("Z")

    assert obj["phase"]["id"] == "STEADY"
    assert obj["phase"]["status"] == "COMPLETED"
    assert obj["phase"]["connections"] == 4
    assert obj["phase"]["start_timestamp"].endswith("Z")

    assert obj["totals"]["requests"] == 7
    assert obj["totals"]["errors"] == 1

    # Command keys are uppercased.
    assert set(obj["metrics"].keys()) == {"GET", "SET"}
    get = obj["metrics"]["GET"]
    assert get["requests"] == 5
    assert get["errors"] == 0
    assert get["latency"]["unit"] == "us"
    assert get["latency"]["count"] == 5
    summary = get["latency"]["summary"]
    assert set(summary.keys()) == {"min", "p50", "p95", "p99", "p999", "max"}
    assert all(isinstance(v, int) for v in summary.values())
    assert get["latency"]["hdr"]["format"] == "hdr"
    assert get["latency"]["hdr"]["sigfig"] == 3
    assert get["latency"]["hdr"]["payload_b64"].startswith("HIST")

    # SET had 1 success + 1 error.
    assert obj["metrics"]["SET"]["requests"] == 2
    assert obj["metrics"]["SET"]["errors"] == 1
