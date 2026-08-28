"""Tests for build-once-per-sweep engine resolution and building."""
import json
import re
import subprocess
from pathlib import Path

import pytest

from run_benchmark_matrix import DRIVER_ENGINE_MAP, build_engines, engines_for_combos

MAKEFILE = Path(__file__).parent.parent.parent / "Makefile"


def write_driver(tmp_path, name, driver_id):
    p = tmp_path / f"{name}.json"
    p.write_text(json.dumps({"driver_id": driver_id, "mode": "standalone"}))
    return str(p)


def combo(driver_config, label="s"):
    return {"label": label, "driver_config": driver_config, "params": {}, "bindings": {}}


@pytest.fixture
def stub_make(monkeypatch):
    """Replace subprocess.run with a stub; returns the list of commands it saw."""
    def install(returncode=0):
        calls = []

        def fake_run(cmd, *args, **kwargs):
            calls.append(cmd)
            return subprocess.CompletedProcess(cmd, returncode)

        monkeypatch.setattr(subprocess, "run", fake_run)
        return calls

    return install


class TestEnginesForCombos:
    def test_one_entry_per_distinct_driver_config(self, tmp_path):
        jedis = write_driver(tmp_path, "jedis", "jedis")
        combos = [combo(jedis, f"pool={n}") for n in (8, 16, 32)]

        assert engines_for_combos(combos) == {jedis: "java"}

    def test_mixed_matrix_needs_every_engine(self, tmp_path):
        drivers = [
            write_driver(tmp_path, "jedis", "jedis"),
            write_driver(tmp_path, "redis-rb", "redis-rb"),
            write_driver(tmp_path, "se-redis", "stackexchange-redis"),
        ]
        engine_by_driver = engines_for_combos([combo(d, d) for d in drivers])

        assert sorted(set(engine_by_driver.values())) == ["csharp", "java", "ruby"]

    def test_unknown_driver_id_falls_back_to_java(self, tmp_path):
        unknown = write_driver(tmp_path, "unknown", "no-such-driver")

        assert engines_for_combos([combo(unknown)]) == {unknown: "java"}


class TestBuildEngines:
    def test_builds_each_engine_once(self, stub_make):
        calls = stub_make()

        build_engines(["csharp", "java"])

        assert calls == [["make", "csharp-build"], ["make", "java-build"]]

    def test_java_only_matrix_does_not_build_dotnet_or_ruby(self, tmp_path, stub_make):
        """A Java-only matrix must not pay for the .NET or Ruby build."""
        combos = [
            combo(write_driver(tmp_path, "jedis", "jedis"), "jedis"),
            combo(write_driver(tmp_path, "lettuce", "lettuce"), "lettuce"),
        ]
        calls = stub_make()

        build_engines(sorted(set(engines_for_combos(combos).values())))

        assert calls == [["make", "java-build"]]

    def test_build_failure_aborts_before_running_later_engines(self, stub_make):
        calls = stub_make(returncode=2)

        with pytest.raises(SystemExit) as excinfo:
            build_engines(["java", "ruby"])

        assert calls == [["make", "java-build"]]
        assert "java-build" in str(excinfo.value)


class TestMakefileContract:
    """Every engine the orchestrator can dispatch to needs both make targets."""

    @pytest.mark.parametrize("engine", sorted(set(DRIVER_ENGINE_MAP.values())))
    def test_engine_has_build_and_run_nobuild_targets(self, engine):
        makefile = MAKEFILE.read_text()
        for target in (f"{engine}-build", f"{engine}-run-nobuild"):
            assert re.search(rf"^{re.escape(target)}\s*:", makefile, re.MULTILINE), \
                f"Makefile has no '{target}' rule, but DRIVER_ENGINE_MAP maps a driver to '{engine}'"
