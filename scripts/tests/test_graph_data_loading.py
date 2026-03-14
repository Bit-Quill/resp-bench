"""Tests for graph data loading — legacy and flat layouts, outlier filtering, CPU, latency."""
import json
import pytest
from pathlib import Path
from fixtures import (
    write_legacy_fixtures, write_flat_fixtures, write_flat_with_cpu,
    make_steady_record, write_ndjson,
)
from generate_interactive_graphs import (
    detect_layout, load_manifest, load_all_data, load_all_data_flat,
    load_latency_data, load_latency_data_flat,
    load_cpu_data_flat, extract_driver_versions, extract_driver_versions_flat,
    assign_variant_colors, _infer_driver_name, _build_display_label,
)


class TestLayoutDetection:
    def test_legacy_layout_detected(self, tmp_path):
        d = tmp_path / "4-clients"
        d.mkdir()
        (d / "jedis.ndjson").write_text("{}")
        assert detect_layout(tmp_path) == "legacy"

    def test_flat_layout_detected(self, tmp_path):
        (tmp_path / "variant-a.ndjson").write_text("{}")
        assert detect_layout(tmp_path) == "flat"

    def test_legacy_preferred_over_flat(self, tmp_path):
        """If both client dirs and flat files exist, legacy wins."""
        d = tmp_path / "4-clients"
        d.mkdir()
        (d / "jedis.ndjson").write_text("{}")
        (tmp_path / "variant.ndjson").write_text("{}")
        assert detect_layout(tmp_path) == "legacy"

    def test_empty_dir_defaults_legacy(self, tmp_path):
        assert detect_layout(tmp_path) == "legacy"


class TestManifestLoading:
    def test_load_manifest(self, tmp_path):
        manifest = {"description": "test", "variants": {"a": {"driver_name": "jedis"}}}
        (tmp_path / "_manifest.json").write_text(json.dumps(manifest))
        result = load_manifest(tmp_path)
        assert result["description"] == "test"
        assert "a" in result["variants"]

    def test_missing_manifest_returns_none(self, tmp_path):
        assert load_manifest(tmp_path) is None

    def test_invalid_manifest_returns_none(self, tmp_path):
        (tmp_path / "_manifest.json").write_text("not json")
        assert load_manifest(tmp_path) is None


class TestLegacyDataLoading:
    def test_loads_two_drivers_two_client_counts(self, tmp_path):
        results_dir = write_legacy_fixtures(
            tmp_path, ["jedis", "lettuce"], [1, 4], iterations=3)
        data, stats, outlier_map = load_all_data(results_dir)

        assert "jedis" in data
        assert "lettuce" in data
        assert len(data["jedis"]) == 2  # 2 client counts
        assert len(data["lettuce"]) == 2
        assert stats["total_runs"] == 12  # 2 drivers × 2 cc × 3 iters
        # Data points should be sorted by client count
        assert data["jedis"][0][0] < data["jedis"][1][0]

    def test_extracts_latency_data(self, tmp_path):
        results_dir = write_legacy_fixtures(tmp_path, ["jedis"], [1], iterations=3)
        data, _, outlier_map = load_all_data(results_dir)
        lat_data, commands = load_latency_data(results_dir, outlier_map)

        assert "GET" in commands
        assert "SET" in commands
        assert ("jedis", "GET", "p50") in lat_data
        assert len(lat_data[("jedis", "GET", "p50")]) == 1  # 1 client count

    def test_extracts_driver_versions(self, tmp_path):
        results_dir = write_legacy_fixtures(tmp_path, ["jedis"], [1], iterations=1)
        versions = extract_driver_versions(results_dir)
        assert "jedis" in versions
        assert versions["jedis"]["primary_version"] == "1.0.0"


class TestFlatDataLoading:
    def test_loads_two_series(self, tmp_path):
        series = [
            {"label": "variant-a", "base_rps": 10000, "rps_per_client": 1000},
            {"label": "variant-b", "base_rps": 20000, "rps_per_client": 2000},
        ]
        results_dir = write_flat_fixtures(tmp_path, series, [1, 4], iterations=3)
        data, stats, outlier_map = load_all_data_flat(results_dir)

        assert "variant-a" in data
        assert "variant-b" in data
        assert len(data["variant-a"]) == 2
        assert stats["total_runs"] == 12

    def test_groups_by_connections(self, tmp_path):
        series = [{"label": "test", "base_rps": 10000, "rps_per_client": 5000}]
        results_dir = write_flat_fixtures(tmp_path, series, [1, 4, 16], iterations=3)
        data, _, _ = load_all_data_flat(results_dir)

        assert len(data["test"]) == 3  # 3 client counts
        client_counts = [cc for cc, _ in data["test"]]
        assert client_counts == [1, 4, 16]

    def test_higher_rps_for_more_clients(self, tmp_path):
        series = [{"label": "test", "base_rps": 10000, "rps_per_client": 5000}]
        results_dir = write_flat_fixtures(tmp_path, series, [1, 16], iterations=3)
        data, _, _ = load_all_data_flat(results_dir)

        rps_1 = data["test"][0][1]
        rps_16 = data["test"][1][1]
        assert rps_16 > rps_1

    def test_latency_data_flat(self, tmp_path):
        series = [{"label": "test", "base_rps": 10000}]
        results_dir = write_flat_fixtures(tmp_path, series, [1, 4], iterations=3)
        _, _, outlier_map = load_all_data_flat(results_dir)
        lat_data, commands = load_latency_data_flat(results_dir, outlier_map)

        assert "GET" in commands
        assert ("test", "GET", "p50") in lat_data

    def test_driver_versions_flat(self, tmp_path):
        series = [{"label": "test", "driver_id": "jedis", "primary_version": "5.2.0"}]
        results_dir = write_flat_fixtures(tmp_path, series, [1], iterations=1)
        versions = extract_driver_versions_flat(results_dir)
        assert "test" in versions
        assert versions["test"]["primary_version"] == "5.2.0"

    def test_skips_cpu_ndjson_files(self, tmp_path):
        series = [{"label": "test", "base_rps": 10000}]
        results_dir = write_flat_with_cpu(tmp_path, series, [1], iterations=3)
        data, _, _ = load_all_data_flat(results_dir)
        # Should only have "test", not "test.cpu"
        assert "test" in data
        assert len(data) == 1

    def test_skips_manifest_file(self, tmp_path):
        manifest = {"description": "test", "variants": {}}
        series = [{"label": "test", "base_rps": 10000}]
        results_dir = write_flat_fixtures(tmp_path, series, [1], iterations=3, manifest=manifest)
        data, _, _ = load_all_data_flat(results_dir)
        assert "_manifest" not in data


class TestOutlierFilteringInLoading:
    def test_outlier_excluded_from_average(self, tmp_path):
        """Inject one extreme outlier run among clean runs."""
        results_dir = tmp_path / "results"
        (results_dir / "1-clients").mkdir(parents=True)

        records = []
        # 9 normal runs
        for i in range(9):
            records.append(make_steady_record(
                connections=1, total_requests=10000, duration_ms=10000,
                start_epoch=1700000000.0 + i * 15))
        # 1 extreme outlier: 10x the RPS
        records.append(make_steady_record(
            connections=1, total_requests=100000, duration_ms=10000,
            start_epoch=1700000000.0 + 200))

        write_ndjson(results_dir / "1-clients" / "jedis.ndjson", records)

        data, stats, _ = load_all_data(results_dir)
        assert stats["discarded_runs"] >= 1
        assert stats["kept_runs"] < 10

        # The average should be close to the clean value, not inflated by outlier
        avg_rps = data["jedis"][0][1]
        assert avg_rps < 2000  # Clean RPS is ~1000, outlier would push to ~1900


class TestCpuDataFlat:
    def test_cpu_data_loaded(self, tmp_path):
        series = [{"label": "test", "base_rps": 10000}]
        results_dir = write_flat_with_cpu(tmp_path, series, [1, 4], iterations=3, cpu_percent=50.0)
        _, _, outlier_map = load_all_data_flat(results_dir)
        cpu_data, has_cpu = load_cpu_data_flat(results_dir, outlier_map)

        assert has_cpu
        assert "test" in cpu_data
        assert len(cpu_data["test"]) == 2  # 2 client counts


class TestColorAssignment:
    def test_single_variant_gets_standard_color(self):
        colors, dashes, families, labels = assign_variant_colors(["jedis"])
        assert "jedis" in colors
        assert dashes["jedis"] == "solid"

    def test_multiple_variants_get_different_shades(self):
        series = ["sdv-glide_a", "sdv-glide_b", "sdv-glide_c"]
        manifest = {
            "variants": {
                "sdv-glide_a": {"driver_name": "spring-data-valkey-glide"},
                "sdv-glide_b": {"driver_name": "spring-data-valkey-glide"},
                "sdv-glide_c": {"driver_name": "spring-data-valkey-glide"},
            }
        }
        colors, dashes, families, labels = assign_variant_colors(series, manifest)
        # All 3 should have different colors
        color_set = {colors[s] for s in series}
        assert len(color_set) == 3
        # All should be in same family
        assert all(families[s] == "spring-data-valkey" for s in series)

    def test_driver_inference_from_manifest(self):
        manifest = {"variants": {"my-variant": {"driver_name": "jedis"}}}
        assert _infer_driver_name("my-variant", manifest) == "jedis"

    def test_driver_inference_heuristic(self):
        assert _infer_driver_name("spring-data-valkey-glide@cb=16") == "spring-data-valkey-glide"
        assert _infer_driver_name("jedis") == "jedis"

    def test_display_label_from_manifest(self):
        manifest = {
            "variants": {
                "v1": {
                    "driver_name": "sdv-glide",
                    "params": {"env": {"GLIDE_TOKIO_WORKER_THREADS": "16"}},
                    "bindings": {"pool_size": "$connections"},
                }
            }
        }
        label = _build_display_label("v1", manifest)
        assert "sdv-glide" in label
        assert "pool_size=connections" in label

    def test_display_label_without_manifest(self):
        assert _build_display_label("raw-label") == "raw-label"
