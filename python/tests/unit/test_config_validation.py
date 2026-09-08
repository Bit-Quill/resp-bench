"""Config validation: reject configs that would silently run zero work.

Ported from the Java reference's CompletionConfig.validate(), plus keyspace
validation so an unusable keyspace fails at load instead of crashing a worker.
"""

import pytest

from resp_bench.config.loader import ConfigLoader


def _workload(completion, keyspace=None, connections=1, commands=None):
    return {
        "schema_version": "1.0",
        "benchmark_profile": {"name": "t"},
        "phases": [
            {
                "id": "P",
                "connections": connections,
                "completion": completion,
                "keyspace": keyspace
                or {"keys_count": 10, "key_prefix": "k:", "generation_alg": "sequential_int"},
                "commands": commands if commands is not None else [{"command": "get", "weight": 1.0}],
            }
        ],
    }


@pytest.mark.parametrize(
    "completion, expected",
    [
        ({"type": "seconds", "seconds": 2}, "Unknown completion type"),
        ({"type": "requests"}, "completion.requests must be a positive integer"),
        ({"type": "duration"}, "completion.seconds must be a positive integer"),
        ({"type": "requests", "requests": 0}, "completion.requests must be a positive integer"),
        ({"type": "duration", "seconds": 0}, "completion.seconds must be a positive integer"),
        ({}, "completion.type is required"),
    ],
)
def test_invalid_completion_rejected(completion, expected):
    with pytest.raises(ValueError, match=expected):
        ConfigLoader.parse_workload_config(_workload(completion))


@pytest.mark.parametrize("type_str", ["duration", "Duration", "DURATION", " duration "])
def test_completion_type_is_case_insensitive(type_str):
    # Java compares with equalsIgnoreCase and C# with OrdinalIgnoreCase, so a
    # config that works there must work here rather than silently running 0 requests.
    wl = ConfigLoader.parse_workload_config(_workload({"type": type_str, "seconds": 2}))
    phase = wl.phases[0]
    assert phase.completion.is_duration_based()
    assert phase.completion.duration_seconds() == 2


@pytest.mark.parametrize(
    "keyspace, expected",
    [
        ({"keys_count": 0, "key_prefix": "k:", "generation_alg": "sequential_int"},
         "keys_count must be a positive integer"),
        ({"key_prefix": "k:", "generation_alg": "sequential_int"},
         "keys_count must be a positive integer"),
        ({"keys_count": 10, "key_prefix": "k:", "generation_alg": "shuffle"},
         "Unknown keyspace.generation_alg"),
    ],
)
def test_invalid_keyspace_rejected(keyspace, expected):
    with pytest.raises(ValueError, match=expected):
        ConfigLoader.parse_workload_config(
            _workload({"type": "requests", "requests": 10}, keyspace=keyspace)
        )


def test_invalid_connections_and_commands_rejected():
    with pytest.raises(ValueError, match="connections must be a positive integer"):
        ConfigLoader.parse_workload_config(
            _workload({"type": "requests", "requests": 10}, connections=0)
        )
    with pytest.raises(ValueError, match="commands must contain at least one entry"):
        ConfigLoader.parse_workload_config(
            _workload({"type": "requests", "requests": 10}, commands=[])
        )


def test_unsupported_command_rejected_at_load():
    # The shared workload schema allows these; this engine has not implemented
    # them, and without validation they would raise only after every connection
    # had been opened.
    with pytest.raises(ValueError, match="unsupported command"):
        ConfigLoader.parse_workload_config(
            _workload(
                {"type": "requests", "requests": 10},
                commands=[{"command": "hset", "weight": 1.0}],
            )
        )


def test_zero_total_weight_rejected():
    # All-zero weights make CommandSelector fall through to the last command for
    # every pick, silently running a different workload than configured.
    with pytest.raises(ValueError, match="weights must sum to a positive value"):
        ConfigLoader.parse_workload_config(
            _workload(
                {"type": "requests", "requests": 10},
                commands=[{"command": "get", "weight": 0}, {"command": "set", "weight": 0}],
            )
        )


def test_error_names_the_phase():
    with pytest.raises(ValueError, match="invalid phase 'P'"):
        ConfigLoader.parse_workload_config(_workload({"type": "nope"}))
