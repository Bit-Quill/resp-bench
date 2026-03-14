"""Tests for matrix config parsing, combo generation, bindings, and applies_to."""
import json
import pytest
from pathlib import Path
from run_benchmark_matrix import (
    parse_matrix_config,
    generate_series_combos,
    resolve_binding,
    generate_workload,
    generate_driver_config,
    DimensionSpec,
)


@pytest.fixture
def simple_matrix(tmp_path):
    """Matrix with 1 driver × connections only."""
    config = {
        "x_axis": "connections",
        "workload_template": "configs/workloads/reference/basic-standalone-single-client-1M-reqs.json",
        "dimensions": {
            "connections": [1, 4, 16],
            "driver_config": ["configs/drivers/high-throughput/jedis.json"],
        },
    }
    p = tmp_path / "matrix.json"
    p.write_text(json.dumps(config))
    return p


@pytest.fixture
def multi_driver_matrix(tmp_path):
    """Matrix with 3 drivers."""
    config = {
        "x_axis": "connections",
        "workload_template": "configs/workloads/reference/basic-standalone-single-client-1M-reqs.json",
        "dimensions": {
            "connections": [1, 4],
            "driver_config": [
                "configs/drivers/high-throughput/jedis.json",
                "configs/drivers/high-throughput/lettuce.json",
                "configs/drivers/high-throughput/valkey-glide.json",
            ],
        },
    }
    p = tmp_path / "matrix.json"
    p.write_text(json.dumps(config))
    return p


@pytest.fixture
def binding_matrix(tmp_path):
    """Matrix with pool_size binding."""
    config = {
        "x_axis": "connections",
        "workload_template": "configs/workloads/reference/basic-standalone-single-client-1M-reqs.json",
        "dimensions": {
            "connections": [1, 4, 16],
            "driver_config": ["configs/drivers/high-throughput/spring-data-valkey-glide.json"],
            "pool_size": "$connections",
        },
    }
    p = tmp_path / "matrix.json"
    p.write_text(json.dumps(config))
    return p


@pytest.fixture
def mixed_binding_matrix(tmp_path):
    """Matrix with pool_size as [8, "$connections"]."""
    config = {
        "x_axis": "connections",
        "workload_template": "configs/workloads/reference/basic-standalone-single-client-1M-reqs.json",
        "dimensions": {
            "connections": [1, 4],
            "driver_config": ["configs/drivers/high-throughput/spring-data-valkey-lettuce.json"],
            "pool_size": [8, "$connections"],
        },
    }
    p = tmp_path / "matrix.json"
    p.write_text(json.dumps(config))
    return p


@pytest.fixture
def applies_to_matrix(tmp_path):
    """Matrix with conditional dimensions."""
    config = {
        "x_axis": "connections",
        "workload_template": "configs/workloads/reference/basic-standalone-single-client-1M-reqs.json",
        "dimensions": {
            "connections": [1, 4],
            "driver_config": [
                "configs/drivers/high-throughput/spring-data-valkey-glide.json",
                "configs/drivers/high-throughput/jedis.json",
            ],
            "env": {
                "values": [
                    {"GLIDE_TOKIO_WORKER_THREADS": "16", "GLIDE_CALLBACK_WORKER_THREADS": "16"},
                ],
                "applies_to": {"driver_config": ["*glide*"]},
            },
            "pool_size": {
                "values": ["$connections"],
                "applies_to": {"driver_config": ["*spring-data*"]},
            },
        },
    }
    p = tmp_path / "matrix.json"
    p.write_text(json.dumps(config))
    return p


@pytest.fixture
def env_sweep_matrix(tmp_path):
    """Matrix with multiple env configs."""
    config = {
        "x_axis": "connections",
        "workload_template": "configs/workloads/reference/basic-standalone-single-client-1M-reqs.json",
        "dimensions": {
            "connections": [1, 4],
            "driver_config": ["configs/drivers/high-throughput/spring-data-valkey-glide.json"],
            "env": [
                {"GLIDE_TOKIO_WORKER_THREADS": "8", "GLIDE_CALLBACK_WORKER_THREADS": "8"},
                {"GLIDE_TOKIO_WORKER_THREADS": "16", "GLIDE_CALLBACK_WORKER_THREADS": "16"},
            ],
            "pool_size": "$connections",
        },
    }
    p = tmp_path / "matrix.json"
    p.write_text(json.dumps(config))
    return p


class TestConfigParsing:
    def test_simple_config(self, simple_matrix):
        config = parse_matrix_config(simple_matrix)
        assert config["x_axis"] == "connections"
        assert "connections" in config["dimensions"]
        assert "driver_config" in config["dimensions"]

    def test_default_iterations(self, simple_matrix):
        config = parse_matrix_config(simple_matrix)
        assert config["iterations"] == 10

    def test_missing_driver_config_raises(self, tmp_path):
        config = {
            "x_axis": "connections",
            "workload_template": "configs/workloads/reference/basic-standalone-single-client-1M-reqs.json",
            "dimensions": {"connections": [1, 4]},
        }
        p = tmp_path / "bad.json"
        p.write_text(json.dumps(config))
        with pytest.raises(ValueError, match="driver_config"):
            parse_matrix_config(p)

    def test_missing_workload_template_raises(self, tmp_path):
        config = {
            "x_axis": "connections",
            "dimensions": {"connections": [1], "driver_config": ["foo.json"]},
        }
        p = tmp_path / "bad.json"
        p.write_text(json.dumps(config))
        with pytest.raises(ValueError, match="workload_template"):
            parse_matrix_config(p)

    def test_missing_x_axis_raises(self, tmp_path):
        config = {
            "x_axis": "nonexistent",
            "workload_template": "w.json",
            "dimensions": {"connections": [1], "driver_config": ["foo.json"]},
        }
        p = tmp_path / "bad.json"
        p.write_text(json.dumps(config))
        with pytest.raises(ValueError, match="nonexistent"):
            parse_matrix_config(p)


class TestSeriesComboGeneration:
    def test_single_driver_no_series_dims(self, simple_matrix):
        config = parse_matrix_config(simple_matrix)
        combos = generate_series_combos(config)
        assert len(combos) == 1
        assert combos[0]["label"] == "jedis"
        assert combos[0]["params"] == {}
        assert combos[0]["bindings"] == {}

    def test_multiple_drivers(self, multi_driver_matrix):
        config = parse_matrix_config(multi_driver_matrix)
        combos = generate_series_combos(config)
        assert len(combos) == 3
        labels = {c["label"] for c in combos}
        assert "jedis" in labels
        assert "lettuce" in labels
        assert "valkey-glide" in labels

    def test_binding_produces_one_series(self, binding_matrix):
        config = parse_matrix_config(binding_matrix)
        combos = generate_series_combos(config)
        assert len(combos) == 1
        assert "pool_size" in combos[0]["bindings"]
        assert combos[0]["bindings"]["pool_size"] == "$connections"

    def test_mixed_binding_produces_two_series(self, mixed_binding_matrix):
        config = parse_matrix_config(mixed_binding_matrix)
        combos = generate_series_combos(config)
        assert len(combos) == 2
        # One with fixed pool_size=8, one with binding
        has_fixed = any(c["params"].get("pool_size") == 8 for c in combos)
        has_binding = any("pool_size" in c.get("bindings", {}) for c in combos)
        assert has_fixed
        assert has_binding

    def test_env_sweep_produces_two_series(self, env_sweep_matrix):
        config = parse_matrix_config(env_sweep_matrix)
        combos = generate_series_combos(config)
        assert len(combos) == 2
        # Both should have env params
        for c in combos:
            assert "env" in c["params"]

    def test_applies_to_filters_correctly(self, applies_to_matrix):
        config = parse_matrix_config(applies_to_matrix)
        combos = generate_series_combos(config)
        # glide driver gets env + pool_size binding
        # jedis driver gets neither (no matching applies_to)
        labels = {c["label"] for c in combos}
        assert len(combos) == 2  # one for glide (with env+pool), one for jedis (bare)

        jedis_combo = next(c for c in combos if "jedis" in c["label"])
        glide_combo = next(c for c in combos if "glide" in c["label"])

        assert jedis_combo["params"] == {}
        assert jedis_combo["bindings"] == {}

        assert "env" in glide_combo["params"]
        assert "pool_size" in glide_combo["bindings"]


class TestBindingResolution:
    def test_resolve_simple_binding(self):
        result = resolve_binding("$connections", {"connections": 16})
        assert result == 16

    def test_resolve_non_binding_passthrough(self):
        assert resolve_binding(8, {"connections": 16}) == 8
        assert resolve_binding("fixed", {"connections": 16}) == "fixed"

    def test_resolve_unknown_binding_raises(self):
        with pytest.raises(ValueError, match="unknown dimension"):
            resolve_binding("$nonexistent", {"connections": 16})


class TestWorkloadGeneration:
    def test_connections_set_in_workload(self, tmp_path):
        template = {
            "phases": [
                {"id": "STEADY", "connections": 1, "commands": []},
                {"id": "WARMUP", "connections": 1, "commands": []},
            ]
        }
        template_path = tmp_path / "template.json"
        template_path.write_text(json.dumps(template))

        workload = generate_workload(str(template_path), 32)
        assert workload["phases"][0]["connections"] == 32
        assert workload["phases"][1]["connections"] == 32


class TestDriverConfigGeneration:
    def test_overrides_applied(self, tmp_path):
        base = {
            "driver_id": "spring-data-valkey",
            "specific_driver_config": {"secondary_driver_id": "valkey-glide"},
        }
        base_path = tmp_path / "driver.json"
        base_path.write_text(json.dumps(base))

        result = generate_driver_config(str(base_path), {"pool_size": 16, "use_pooling": True})
        assert result["specific_driver_config"]["pool_size"] == 16
        assert result["specific_driver_config"]["use_pooling"] is True
        assert result["specific_driver_config"]["secondary_driver_id"] == "valkey-glide"

    def test_no_overrides_preserves_original(self, tmp_path):
        base = {"driver_id": "jedis", "specific_driver_config": {}}
        base_path = tmp_path / "driver.json"
        base_path.write_text(json.dumps(base))

        result = generate_driver_config(str(base_path), {})
        assert result["driver_id"] == "jedis"


class TestDimensionSpec:
    def test_array_dimension(self):
        d = DimensionSpec("connections", [1, 4, 16])
        assert d.values == [1, 4, 16]
        assert d.applies_to is None
        assert not d.is_binding_only
        assert not d.is_scalar

    def test_binding_dimension(self):
        d = DimensionSpec("pool_size", "$connections")
        assert d.values == ["$connections"]
        assert d.is_binding_only

    def test_scalar_dimension(self):
        d = DimensionSpec("use_pooling", True)
        assert d.values == [True]
        assert d.is_scalar

    def test_extended_with_applies_to(self):
        d = DimensionSpec("env", {
            "values": [{"K": "V"}],
            "applies_to": {"driver_config": ["*glide*"]},
        })
        assert d.values == [{"K": "V"}]
        assert d.applies_to == {"driver_config": ["*glide*"]}

    def test_matches_driver_glob(self):
        d = DimensionSpec("env", {
            "values": [1],
            "applies_to": {"driver_config": ["*glide*"]},
        })
        assert d.matches_driver("configs/drivers/high-throughput/spring-data-valkey-glide.json")
        assert not d.matches_driver("configs/drivers/high-throughput/jedis.json")

    def test_no_applies_to_matches_all(self):
        d = DimensionSpec("pool_size", [8, 16])
        assert d.matches_driver("anything.json")
