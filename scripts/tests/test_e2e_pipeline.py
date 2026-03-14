"""
End-to-end integration tests: matrix orchestrator → real Java engine (recording client) → graph generator.

These tests exercise the full pipeline:
  1. run_benchmark_matrix.py executes with real Java engine + recording client
  2. Engine produces .ndjson with known latency distribution
  3. SystemMonitor produces .system.ndjson with real CPU/memory readings
  4. generate_interactive_graphs.py produces HTML with Plotly charts
  5. Tests verify: NDJSON values match expectations, graph data matches NDJSON, system metrics are plausible

Prerequisites:
  - Java 21+ and Maven (for java-build)
  - No Redis/Valkey server needed (recording client is serverless)
  - Run with: cd scripts && python -m pytest tests/test_e2e_pipeline.py -v

Mark: these tests are tagged with 'integration' for selective running.
"""
import json
import os
import re
import subprocess
import sys
import pytest
from pathlib import Path

# Project root (repo root, not scripts/)
PROJECT_ROOT = Path(__file__).parent.parent.parent


# ═══════════════════════════════════════════════════════════════════════════════
# Helpers
# ═══════════════════════════════════════════════════════════════════════════════

def run_matrix(matrix_config, output_dir):
    """Run the benchmark matrix orchestrator (real execution, not dry-run)."""
    result = subprocess.run(
        [
            sys.executable,
            str(PROJECT_ROOT / "scripts" / "run_benchmark_matrix.py"),
            "--matrix", str(matrix_config),
            "--output-dir", str(output_dir),
            "--server-host", "localhost",  # recording client ignores this
        ],
        cwd=str(PROJECT_ROOT),
        capture_output=True,
        text=True,
        timeout=120,
    )
    if result.returncode != 0:
        print(f"STDOUT:\n{result.stdout}")
        print(f"STDERR:\n{result.stderr}")
    return result


def run_graph_generator(results_dir, output_dir, reference=None):
    """Run the interactive graph generator."""
    cmd = [
        sys.executable,
        str(PROJECT_ROOT / "scripts" / "generate_interactive_graphs.py"),
        str(results_dir),
        "--output", str(output_dir),
    ]
    if reference:
        cmd.extend(["--reference", reference])
    result = subprocess.run(cmd, cwd=str(PROJECT_ROOT), capture_output=True, text=True, timeout=30)
    return result


def load_ndjson_records(ndjson_path):
    """Load all JSON records from an NDJSON file."""
    records = []
    with open(ndjson_path) as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def load_steady_records(ndjson_path):
    """Load only STEADY phase records from an NDJSON file."""
    return [r for r in load_ndjson_records(ndjson_path) if r.get("phase", {}).get("id") == "STEADY"]


def extract_rps_from_record(record):
    """Compute total RPS from a STEADY record."""
    duration_ms = record["phase"]["duration_ms"]
    total_requests = record["totals"]["requests"]
    return total_requests / (duration_ms / 1000.0) if duration_ms > 0 else 0


def extract_plotly_data_from_html(html_path):
    """Extract Plotly trace data from generated HTML.

    Parses the Plotly.newPlot() calls and returns a dict:
        chart_id -> list of trace dicts (each with x, y, name, etc.)
    """
    html = Path(html_path).read_text()
    charts = {}

    # Find all Plotly.newPlot("chart-id", [...traces...], {...layout...}, ...)
    pattern = r'Plotly\.newPlot\("([^"]+)",\s*(\[.*?\]),\s*(\{.*?\}),\s*\{responsive'
    for match in re.finditer(pattern, html, re.DOTALL):
        chart_id = match.group(1)
        traces_json = match.group(2)
        try:
            traces = json.loads(traces_json)
            charts[chart_id] = traces
        except json.JSONDecodeError:
            pass

    return charts


# ═══════════════════════════════════════════════════════════════════════════════
# Fixtures
# ═══════════════════════════════════════════════════════════════════════════════

@pytest.fixture(scope="module")
def java_jar():
    """Ensure Java is built before running e2e tests."""
    jar_path = PROJECT_ROOT / "java" / "target" / "resp-bench-java-1.0.0-SNAPSHOT.jar"
    if not jar_path.exists():
        print("Building Java engine...")
        result = subprocess.run(
            ["make", "java-build"],
            cwd=str(PROJECT_ROOT),
            capture_output=True,
            text=True,
            timeout=300,
        )
        if result.returncode != 0:
            pytest.skip(f"Java build failed: {result.stderr[:500]}")
    return jar_path


# ═══════════════════════════════════════════════════════════════════════════════
# Tests
# ═══════════════════════════════════════════════════════════════════════════════

@pytest.mark.integration
class TestSingleDriverE2E:
    """Single recording client variant across multiple client counts."""

    def test_engine_produces_ndjson_with_correct_values(self, java_jar, tmp_path):
        """Run engine → verify NDJSON has expected request count, latency, connections."""
        output_dir = tmp_path / "results"
        result = run_matrix(PROJECT_ROOT / "configs/test/matrices/matrix-e2e-single.json", output_dir)
        assert result.returncode == 0, f"Matrix run failed: {result.stderr[:500]}"

        # Should produce one NDJSON file for the single variant
        ndjson_files = list(output_dir.glob("*.ndjson"))
        ndjson_files = [f for f in ndjson_files if not f.name.endswith(".system.ndjson")
                        and not f.name.startswith("_")]
        assert len(ndjson_files) == 1, f"Expected 1 NDJSON file, got {[f.name for f in ndjson_files]}"

        records = load_steady_records(ndjson_files[0])
        # Matrix has connections=[1, 4] × 1 iteration = 2 STEADY records
        assert len(records) == 2

        for rec in records:
            # Request count should match workload (200)
            assert rec["totals"]["requests"] == 200

            # Connections should be 1 or 4
            assert rec["phase"]["connections"] in [1, 4]

            # Latency p50 should approximate the recording client's median (100ms = 100,000µs)
            set_p50 = rec["metrics"]["SET"]["latency"]["summary"]["p50"]
            assert 50_000 < set_p50 < 300_000, \
                f"SET p50={set_p50}µs, expected ~100,000µs (100ms recording client)"

            # RPS should be positive and plausible
            rps = extract_rps_from_record(rec)
            assert rps > 0

    def test_graph_rps_matches_ndjson(self, java_jar, tmp_path):
        """Run engine → graph → verify graph RPS data points match NDJSON values."""
        output_dir = tmp_path / "results"
        graph_dir = tmp_path / "graphs"

        result = run_matrix(PROJECT_ROOT / "configs/test/matrices/matrix-e2e-single.json", output_dir)
        assert result.returncode == 0

        result = run_graph_generator(output_dir, graph_dir)
        assert result.returncode == 0

        html_path = graph_dir / "scalability_and_delta.html"
        assert html_path.exists()

        # Load NDJSON data
        ndjson_files = [f for f in output_dir.glob("*.ndjson")
                        if not f.name.endswith(".system.ndjson") and not f.name.startswith("_")]
        records = load_steady_records(ndjson_files[0])

        # Build expected: {connections -> rps}
        ndjson_rps = {}
        for rec in records:
            cc = rec["phase"]["connections"]
            ndjson_rps[cc] = extract_rps_from_record(rec)

        # Parse graph HTML
        charts = extract_plotly_data_from_html(html_path)
        assert "scalability-total" in charts, f"Missing scalability chart. Charts: {list(charts.keys())}"

        # Get the single trace
        traces = charts["scalability-total"]
        assert len(traces) >= 1

        trace = traces[0]
        graph_data = dict(zip(trace["x"], trace["y"]))

        # Verify each data point matches NDJSON
        for cc, expected_rps in ndjson_rps.items():
            assert cc in graph_data, f"Client count {cc} missing from graph"
            # Allow small floating point difference
            assert abs(graph_data[cc] - expected_rps) / expected_rps < 0.01, \
                f"Graph RPS={graph_data[cc]:.0f} != NDJSON RPS={expected_rps:.0f} at connections={cc}"

    def test_graph_latency_matches_ndjson(self, java_jar, tmp_path):
        """Run engine → graph → verify graph latency p50 matches NDJSON values."""
        output_dir = tmp_path / "results"
        graph_dir = tmp_path / "graphs"

        result = run_matrix(PROJECT_ROOT / "configs/test/matrices/matrix-e2e-single.json", output_dir)
        assert result.returncode == 0

        result = run_graph_generator(output_dir, graph_dir)
        assert result.returncode == 0

        html_path = graph_dir / "scalability_and_delta.html"
        charts = extract_plotly_data_from_html(html_path)

        # Check latency chart exists
        assert "latency-set-p50" in charts, f"Missing SET p50 chart. Charts: {list(charts.keys())}"

        # Load NDJSON latency
        ndjson_files = [f for f in output_dir.glob("*.ndjson")
                        if not f.name.endswith(".system.ndjson") and not f.name.startswith("_")]
        records = load_steady_records(ndjson_files[0])

        ndjson_latency = {}
        for rec in records:
            cc = rec["phase"]["connections"]
            ndjson_latency[cc] = rec["metrics"]["SET"]["latency"]["summary"]["p50"]

        # Verify graph latency matches
        traces = charts["latency-set-p50"]
        assert len(traces) >= 1
        trace = traces[0]
        graph_data = dict(zip(trace["x"], trace["y"]))

        for cc, expected_lat in ndjson_latency.items():
            assert cc in graph_data, f"Client count {cc} missing from latency chart"
            assert abs(graph_data[cc] - expected_lat) / max(expected_lat, 1) < 0.01, \
                f"Graph latency={graph_data[cc]} != NDJSON latency={expected_lat} at connections={cc}"

    def test_system_metrics_during_run(self, java_jar, tmp_path):
        """Run engine → verify .system.ndjson has plausible CPU and memory readings."""
        output_dir = tmp_path / "results"
        result = run_matrix(PROJECT_ROOT / "configs/test/matrices/matrix-e2e-single.json", output_dir)
        assert result.returncode == 0

        system_files = list(output_dir.glob("*.system.ndjson"))
        assert len(system_files) == 1, f"Expected 1 system file, got {[f.name for f in system_files]}"

        records = load_ndjson_records(system_files[0])
        assert len(records) >= 2, f"Expected ≥2 system samples, got {len(records)}"

        for rec in records:
            # CPU should be plausible
            assert 0 <= rec["cpu_percent"] <= 100
            assert rec["cpu_cores"] >= 1

            # Memory fields should be present
            assert rec["memory_available_bytes"] is not None
            assert rec["memory_total_bytes"] is not None
            assert rec["memory_total_bytes"] > 0

            # RSS should be non-null (we're tracking the make/java process group)
            # It may be null if the process already exited — check at least some are non-null

        rss_values = [r["memory_rss_bytes"] for r in records if r["memory_rss_bytes"] is not None]
        assert len(rss_values) > 0, "Expected at least one sample with non-null RSS"
        # JVM baseline is always > 20MB
        assert max(rss_values) > 20_000_000, \
            f"Max RSS={max(rss_values)} bytes — expected > 20MB for JVM"


@pytest.mark.integration
class TestMultiVariantE2E:
    """Fast vs slow recording client — verify ordering in NDJSON and graphs."""

    def test_fast_variant_has_higher_rps(self, java_jar, tmp_path):
        """Fast (10ms) recording client should have ~10x more RPS than slow (100ms)."""
        output_dir = tmp_path / "results"
        result = run_matrix(PROJECT_ROOT / "configs/test/matrices/matrix-e2e-multi.json", output_dir)
        assert result.returncode == 0

        ndjson_files = sorted([
            f for f in output_dir.glob("*.ndjson")
            if not f.name.endswith(".system.ndjson") and not f.name.startswith("_")
        ])
        assert len(ndjson_files) == 2, f"Expected 2 NDJSON files, got {[f.name for f in ndjson_files]}"

        rps_by_variant = {}
        for f in ndjson_files:
            records = load_steady_records(f)
            assert len(records) >= 1
            rps = extract_rps_from_record(records[0])
            rps_by_variant[f.stem] = rps

        # Find fast and slow by filename pattern
        fast_key = [k for k in rps_by_variant if "fast" in k.lower()][0]
        slow_key = [k for k in rps_by_variant if "slow" in k.lower()][0]

        assert rps_by_variant[fast_key] > rps_by_variant[slow_key] * 2, \
            f"Fast RPS={rps_by_variant[fast_key]:.0f} should be >> Slow RPS={rps_by_variant[slow_key]:.0f}"

    def test_slow_variant_has_higher_latency(self, java_jar, tmp_path):
        """Slow (100ms) recording client should have ~10x higher p50 latency."""
        output_dir = tmp_path / "results"
        result = run_matrix(PROJECT_ROOT / "configs/test/matrices/matrix-e2e-multi.json", output_dir)
        assert result.returncode == 0

        ndjson_files = sorted([
            f for f in output_dir.glob("*.ndjson")
            if not f.name.endswith(".system.ndjson") and not f.name.startswith("_")
        ])

        latency_by_variant = {}
        for f in ndjson_files:
            records = load_steady_records(f)
            p50 = records[0]["metrics"]["SET"]["latency"]["summary"]["p50"]
            latency_by_variant[f.stem] = p50

        fast_key = [k for k in latency_by_variant if "fast" in k.lower()][0]
        slow_key = [k for k in latency_by_variant if "slow" in k.lower()][0]

        assert latency_by_variant[slow_key] > latency_by_variant[fast_key] * 2, \
            f"Slow p50={latency_by_variant[slow_key]}µs should be >> Fast p50={latency_by_variant[fast_key]}µs"

    def test_graph_has_two_series(self, java_jar, tmp_path):
        """Graph should have 2 series in the scalability chart."""
        output_dir = tmp_path / "results"
        graph_dir = tmp_path / "graphs"

        result = run_matrix(PROJECT_ROOT / "configs/test/matrices/matrix-e2e-multi.json", output_dir)
        assert result.returncode == 0

        result = run_graph_generator(output_dir, graph_dir)
        assert result.returncode == 0

        html_path = graph_dir / "scalability_and_delta.html"
        charts = extract_plotly_data_from_html(html_path)
        assert "scalability-total" in charts

        traces = charts["scalability-total"]
        assert len(traces) == 2, f"Expected 2 series, got {len(traces)}"

    def test_graph_rps_ordering_matches_ndjson(self, java_jar, tmp_path):
        """Graph should show fast variant with higher RPS than slow variant."""
        output_dir = tmp_path / "results"
        graph_dir = tmp_path / "graphs"

        result = run_matrix(PROJECT_ROOT / "configs/test/matrices/matrix-e2e-multi.json", output_dir)
        assert result.returncode == 0

        result = run_graph_generator(output_dir, graph_dir)
        assert result.returncode == 0

        charts = extract_plotly_data_from_html(graph_dir / "scalability_and_delta.html")
        traces = charts["scalability-total"]

        # Each trace has y values — find which is faster
        rps_values = [(t["name"], t["y"][0]) for t in traces]
        rps_values.sort(key=lambda x: x[1], reverse=True)

        # Higher RPS should be the "fast" variant
        assert "fast" in rps_values[0][0].lower() or "slow" in rps_values[1][0].lower(), \
            f"Expected fast variant to have higher RPS. Got: {rps_values}"


@pytest.mark.integration
class TestManifestE2E:
    """Verify _manifest.json is correct after a real matrix run."""

    def test_manifest_has_correct_variants(self, java_jar, tmp_path):
        output_dir = tmp_path / "results"
        result = run_matrix(PROJECT_ROOT / "configs/test/matrices/matrix-e2e-multi.json", output_dir)
        assert result.returncode == 0

        manifest_path = output_dir / "_manifest.json"
        assert manifest_path.exists()

        manifest = json.loads(manifest_path.read_text())
        assert "variants" in manifest
        assert len(manifest["variants"]) == 2

        # Verify variant driver names
        driver_names = {v["driver_name"] for v in manifest["variants"].values()}
        assert "recording-fast" in driver_names
        assert "recording-slow" in driver_names
