from pathlib import Path

from resp_bench.config.loader import ConfigLoader

# repo root: tests/unit/test_x.py -> parents[3]
REPO_ROOT = Path(__file__).resolve().parents[3]
CONFIGS = REPO_ROOT / "configs"


def test_parse_driver_config_defaults():
    cfg = ConfigLoader.parse_driver_config({"driver_id": "redis-py"})
    assert cfg.schema_version == "1.0"
    assert cfg.mode == "standalone"
    assert cfg.specific_driver_config == {}
    assert cfg.command_timeout_ms is None
    assert cfg.is_standalone()


def test_parse_driver_config_top_level_command_timeout():
    # command_timeout_ms is a top-level key (as in the shared config files),
    # not nested under specific_driver_config.
    cfg = ConfigLoader.parse_driver_config(
        {"driver_id": "redis-py", "command_timeout_ms": 10000}
    )
    assert cfg.command_timeout_ms == 10000


def test_command_without_weight_defaults_to_one():
    workload = ConfigLoader.parse_workload_config(
        {
            "schema_version": "1.0",
            "benchmark_profile": {"name": "t"},
            "phases": [
                {
                    "id": "P",
                    "connections": 1,
                    "completion": {"type": "requests", "requests": 1},
                    "keyspace": {"keys_count": 1, "key_prefix": "k:", "generation_alg": "sequential_int"},
                    "commands": [{"command": "ping"}],
                }
            ],
        }
    )
    assert workload.phases[0].commands[0].weight == 1.0


def test_parse_phase_defaults():
    workload = ConfigLoader.parse_workload_config(
        {
            "schema_version": "1.0",
            "benchmark_profile": {"name": "t"},
            "phases": [
                {
                    "id": "P",
                    "connections": 2,
                    "completion": {"type": "requests", "requests": 10},
                    "keyspace": {"keys_count": 5, "key_prefix": "k:", "generation_alg": "sequential_int"},
                    "commands": [{"command": "SET", "weight": 1.0}],
                }
            ],
        }
    )
    phase = workload.phases[0]
    assert phase.cps_limit == -1
    assert phase.rps_limit == -1
    assert phase.pipeline_depth == 1
    assert phase.warmup_requests == 1
    # command name is lowercased at config level; keyspace defaults applied.
    assert phase.commands[0].command == "set"
    assert phase.commands[0].data_size_bytes == 256
    assert phase.keyspace.key_size_bytes == 16


def test_loads_shared_example_workload():
    workload = ConfigLoader.load_workload_config(
        str(CONFIGS / "workloads" / "example-workload.json")
    )
    assert len(workload.phases) == 2
    warmup, steady = workload.phases
    assert warmup.id == "WARMUP"
    assert warmup.completion.is_request_based()
    assert steady.keyspace.is_uniform_rand()
    assert steady.keyspace.seed_value() == 12345


def test_loads_shared_driver_configs():
    for name in ("redis-rb.json", "valkey-glide.json"):
        cfg = ConfigLoader.load_driver_config(str(CONFIGS / "drivers" / "default" / name))
        assert cfg.schema_version == "1.0"
        assert cfg.driver_id
