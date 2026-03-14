"""Tests for HTML graph output — structure, chart presence, stats rendering."""
import pytest
from pathlib import Path
from fixtures import write_legacy_fixtures, write_flat_fixtures
from generate_interactive_graphs import (
    load_all_data, load_all_data_flat, load_latency_data, load_latency_data_flat,
    generate_html, REFERENCE_DRIVER,
)


def _generate_and_read(data, stats, output_dir, title="", **kwargs):
    """Helper: generate HTML and return its content."""
    output_path = output_dir / "test.html"
    generate_html(data, stats, title, output_path, **kwargs)
    return output_path.read_text()


class TestHtmlStructure:
    def test_html_file_created(self, tmp_path):
        results_dir = write_legacy_fixtures(tmp_path, ["jedis"], [1], iterations=3)
        data, stats, _ = load_all_data(results_dir)
        output_path = tmp_path / "out" / "test.html"
        generate_html(data, stats, "", output_path)
        assert output_path.exists()

    def test_contains_plotly_js(self, tmp_path):
        results_dir = write_legacy_fixtures(tmp_path, ["jedis"], [1], iterations=3)
        data, stats, _ = load_all_data(results_dir)
        html = _generate_and_read(data, stats, tmp_path)
        assert "plotly" in html.lower()

    def test_rps_chart_present(self, tmp_path):
        results_dir = write_legacy_fixtures(tmp_path, ["jedis"], [1], iterations=3)
        data, stats, _ = load_all_data(results_dir)
        html = _generate_and_read(data, stats, tmp_path)
        assert 'id="scalability-total"' in html

    def test_delta_chart_present(self, tmp_path):
        results_dir = write_legacy_fixtures(tmp_path, ["jedis"], [1], iterations=3)
        data, stats, _ = load_all_data(results_dir)
        html = _generate_and_read(data, stats, tmp_path)
        assert 'id="delta-total"' in html

    def test_stats_displayed(self, tmp_path):
        results_dir = write_legacy_fixtures(tmp_path, ["jedis", "lettuce"], [1, 4], iterations=5)
        data, stats, _ = load_all_data(results_dir)
        html = _generate_and_read(data, stats, tmp_path)
        assert f"Total runs: {stats['total_runs']}" in html
        assert "Discarded outliers:" in html

    def test_title_prefix(self, tmp_path):
        results_dir = write_legacy_fixtures(tmp_path, ["jedis"], [1], iterations=3)
        data, stats, _ = load_all_data(results_dir)
        html = _generate_and_read(data, stats, tmp_path, title="My Test")
        assert "My Test" in html


class TestLatencyCharts:
    def test_latency_charts_when_data_exists(self, tmp_path):
        results_dir = write_legacy_fixtures(tmp_path, ["jedis"], [1, 4], iterations=3)
        data, stats, outlier_map = load_all_data(results_dir)
        lat_data, commands = load_latency_data(results_dir, outlier_map)
        html = _generate_and_read(data, stats, tmp_path,
                                   latency_data=lat_data, latency_commands=commands)
        assert 'id="latency-get-p50"' in html
        assert 'id="latency-set-p50"' in html

    def test_no_latency_charts_when_no_data(self, tmp_path):
        results_dir = write_legacy_fixtures(tmp_path, ["jedis"], [1], iterations=3)
        data, stats, _ = load_all_data(results_dir)
        html = _generate_and_read(data, stats, tmp_path)
        assert "latency-get-p50" not in html


class TestFlatLayoutHtml:
    def test_flat_layout_generates_html(self, tmp_path):
        series = [
            {"label": "variant-a", "base_rps": 10000},
            {"label": "variant-b", "base_rps": 20000},
        ]
        results_dir = write_flat_fixtures(tmp_path, series, [1, 4], iterations=3)
        data, stats, _ = load_all_data_flat(results_dir)
        html = _generate_and_read(data, stats, tmp_path)
        assert 'id="scalability-total"' in html
        assert stats["total_runs"] == 12
