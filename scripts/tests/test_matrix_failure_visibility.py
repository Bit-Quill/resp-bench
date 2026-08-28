"""Tests for matrix runner failure visibility: CLI resolution, readiness probe,
run isolation, per-cell outcome tracking, and exit codes."""
import json
import os
import stat
import subprocess
import pytest
from pathlib import Path

from run_benchmark_matrix import (
    PreflightError,
    cli_connection_args,
    config_needs_server,
    count_ndjson_lines,
    default_run_id,
    driver_server_settings,
    existing_result_files,
    ndjson_size,
    non_standalone_modes,
    parse_matrix_config,
    prepare_results_dir,
    preflight_server,
    probe_server,
    resolve_cli_path,
    run_matrix,
    summarize_cells,
    unique_server_credentials,
    update_latest_link,
    validate_run_id,
    write_manifest,
)


# ═══════════════════════════════════════════════════════════════════════════════
# Helpers
# ═══════════════════════════════════════════════════════════════════════════════

def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload))
    return path


def make_executable(path, body="#!/bin/sh\nexit 0\n"):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body)
    path.chmod(path.stat().st_mode | stat.S_IXUSR)
    return path


def make_driver(tmp_path, name, driver_id="jedis", **extra):
    payload = {"schema_version": "1.0", "driver_id": driver_id, "mode": "standalone"}
    payload.update(extra)
    return str(write_json(tmp_path / f"{name}.json", payload))


def combos_for(driver_paths):
    return [
        {"label": Path(p).stem, "driver_config": p, "params": {}, "bindings": {}}
        for p in driver_paths
    ]


# ═══════════════════════════════════════════════════════════════════════════════
# CLI resolution
# ═══════════════════════════════════════════════════════════════════════════════

class TestResolveCliPath:
    def test_env_override_wins(self, tmp_path):
        assert resolve_cli_path(env={"RESP_BENCH_CLI": "/custom/vk-cli"},
                                which=lambda name: None,
                                repo_root=tmp_path) == "/custom/vk-cli"

    def test_prefers_makefile_built_binary(self, tmp_path):
        built = make_executable(tmp_path / "work" / "valkey" / "bin" / "valkey-cli")
        resolved = resolve_cli_path(env={}, which=lambda name: "/usr/bin/valkey-cli",
                                    repo_root=tmp_path)
        assert resolved == str(built)

    def test_honors_server_project_override(self, tmp_path):
        make_executable(tmp_path / "work" / "valkey" / "bin" / "valkey-cli")
        built = make_executable(tmp_path / "work" / "redis" / "bin" / "redis-cli")
        resolved = resolve_cli_path(env={"SERVER_PROJECT": "redis"},
                                    which=lambda name: None, repo_root=tmp_path)
        assert resolved == str(built)

    def test_non_executable_built_path_is_skipped(self, tmp_path):
        path = tmp_path / "work" / "valkey" / "bin" / "valkey-cli"
        path.parent.mkdir(parents=True)
        path.write_text("not executable")
        path.chmod(stat.S_IRUSR | stat.S_IWUSR)
        assert resolve_cli_path(env={}, which=lambda name: "/usr/bin/valkey-cli",
                                repo_root=tmp_path) == "/usr/bin/valkey-cli"

    def test_falls_back_to_path_lookup_order(self, tmp_path):
        looked_up = []

        def fake_which(name):
            looked_up.append(name)
            return "/usr/bin/redis-cli" if name == "redis-cli" else None

        assert resolve_cli_path(env={}, which=fake_which,
                                repo_root=tmp_path) == "/usr/bin/redis-cli"
        assert looked_up == ["valkey-cli", "redis-cli"]

    def test_returns_none_when_nothing_found(self, tmp_path):
        assert resolve_cli_path(env={}, which=lambda name: None, repo_root=tmp_path) is None


# ═══════════════════════════════════════════════════════════════════════════════
# Auth / TLS flags
# ═══════════════════════════════════════════════════════════════════════════════

class TestCliConnectionArgs:
    def test_host_and_port_only(self):
        assert cli_connection_args("db1", 6380) == ["-h", "db1", "-p", "6380"]

    def test_password(self):
        args = cli_connection_args("h", 1, auth={"password": "s3cret"})
        assert "-a" in args and args[args.index("-a") + 1] == "s3cret"
        assert "--no-auth-warning" in args

    def test_username_requires_password(self):
        assert "--user" not in cli_connection_args("h", 1, auth={"username": "acl"})
        args = cli_connection_args("h", 1, auth={"username": "acl", "password": "p"})
        assert args[args.index("--user") + 1] == "acl"

    def test_tls_flags(self):
        args = cli_connection_args("h", 1, tls={
            "enabled": True,
            "ca_path": "/ca.crt",
            "cert_path": "/c.crt",
            "key_path": "/c.key",
        })
        assert "--tls" in args
        assert args[args.index("--cacert") + 1] == "/ca.crt"
        assert args[args.index("--cert") + 1] == "/c.crt"
        assert args[args.index("--key") + 1] == "/c.key"
        assert "--insecure" not in args

    def test_tls_disabled_adds_nothing(self):
        assert cli_connection_args("h", 1, tls={"enabled": False, "ca_path": "/ca"}) == \
            ["-h", "h", "-p", "1"]

    def test_verify_hostname_false_is_insecure(self):
        args = cli_connection_args("h", 1, tls={"enabled": True, "verify_hostname": False})
        assert "--insecure" in args


# ═══════════════════════════════════════════════════════════════════════════════
# Which drivers need a server
# ═══════════════════════════════════════════════════════════════════════════════

class TestDriverNeedsServer:
    def test_recording_driver_is_serverless(self):
        assert not config_needs_server({"driver_id": "recording"})
        assert not config_needs_server({"driver_id": "RECORDING"})

    def test_real_driver_needs_server(self):
        assert config_needs_server({"driver_id": "jedis"})

    def test_unreadable_config_assumed_to_need_server(self, tmp_path):
        missing = str(tmp_path / "missing.json")
        assert config_needs_server({})
        assert driver_server_settings(combos_for([missing]))[missing] == ({}, {})

    def test_settings_map_and_unique_credentials(self, tmp_path):
        rec = make_driver(tmp_path, "rec", driver_id="recording")
        plain = make_driver(tmp_path, "plain")
        secured = make_driver(tmp_path, "secured", auth={"password": "p"},
                              tls={"enabled": True})
        settings = driver_server_settings(combos_for([rec, plain, secured, plain]))
        assert settings[rec] is None
        assert settings[plain] == ({}, {})
        assert settings[secured] == ({"password": "p"}, {"enabled": True})
        # plain appears twice but is deduplicated
        assert len(unique_server_credentials(settings)) == 2

    def test_no_credentials_when_all_serverless(self, tmp_path):
        rec = make_driver(tmp_path, "rec", driver_id="recording")
        assert unique_server_credentials(driver_server_settings(combos_for([rec]))) == []


# ═══════════════════════════════════════════════════════════════════════════════
# Readiness probe
# ═══════════════════════════════════════════════════════════════════════════════

class TestProbeServer:
    def test_pong_on_first_attempt(self, tmp_path):
        cli = make_executable(tmp_path / "cli", "#!/bin/sh\necho PONG\n")
        slept = []
        ok, detail = probe_server(str(cli), "h", 1, sleep=slept.append)
        assert ok and detail == "PONG"
        assert slept == []

    def test_retries_until_ready(self, tmp_path):
        counter = tmp_path / "count"
        cli = make_executable(tmp_path / "cli", f"""#!/bin/sh
n=$(cat {counter} 2>/dev/null || echo 0)
n=$((n+1)); echo $n > {counter}
if [ "$n" -lt 3 ]; then echo "Connection refused" >&2; exit 1; fi
echo PONG
""")
        slept = []
        ok, _ = probe_server(str(cli), "h", 1, sleep=slept.append)
        assert ok
        assert len(slept) == 2

    def test_gives_up_after_bounded_attempts(self, tmp_path):
        cli = make_executable(tmp_path / "cli",
                              '#!/bin/sh\necho "Connection refused" >&2\nexit 1\n')
        slept = []
        ok, detail = probe_server(str(cli), "h", 1, attempts=3, sleep=slept.append)
        assert not ok
        assert "Connection refused" in detail
        assert len(slept) == 2

    def test_missing_binary_is_reported_not_raised(self, tmp_path):
        ok, detail = probe_server(str(tmp_path / "nope"), "h", 1, attempts=1,
                                  sleep=lambda _: None)
        assert not ok and detail


class TestPreflightServer:
    def test_skips_probe_when_no_driver_needs_server(self, tmp_path):
        rec = make_driver(tmp_path, "rec", driver_id="recording")
        settings = driver_server_settings(combos_for([rec]))
        assert preflight_server(settings, "h", 1, cli_path="/does/not/exist") is None

    def test_raises_when_server_unreachable(self, tmp_path):
        cli = make_executable(tmp_path / "cli", "#!/bin/sh\nexit 1\n")
        settings = driver_server_settings(combos_for([make_driver(tmp_path, "jedis")]))
        with pytest.raises(PreflightError, match="did not answer PING"):
            preflight_server(settings, "h", 1, cli_path=str(cli), attempts=1, delay=0)

    def test_raises_when_no_cli_found(self, tmp_path, monkeypatch):
        monkeypatch.setattr("run_benchmark_matrix.resolve_cli_path", lambda: None)
        settings = driver_server_settings(combos_for([make_driver(tmp_path, "jedis")]))
        with pytest.raises(PreflightError, match="no valkey-cli binary found"):
            preflight_server(settings, "h", 1)

    def test_returns_cli_when_ready(self, tmp_path):
        cli = make_executable(tmp_path / "cli", "#!/bin/sh\necho PONG\n")
        settings = driver_server_settings(combos_for([make_driver(tmp_path, "jedis")]))
        assert preflight_server(settings, "h", 1, cli_path=str(cli)) == str(cli)


# ═══════════════════════════════════════════════════════════════════════════════
# Run isolation
# ═══════════════════════════════════════════════════════════════════════════════

class TestPrepareResultsDir:
    def test_creates_run_subdirectory(self, tmp_path):
        results = prepare_results_dir(tmp_path / "out", "run-1")
        assert results == tmp_path / "out" / "run-1"
        assert results.is_dir()

    def test_default_run_id_is_utc_timestamp(self):
        run_id = default_run_id()
        assert len(run_id) == 16 and run_id[8] == "T" and run_id.endswith("Z")

    def test_refuses_populated_directory(self, tmp_path):
        results = prepare_results_dir(tmp_path / "out", "run-1")
        (results / "jedis.ndjson").write_text("{}\n")
        with pytest.raises(PreflightError, match="already contains results"):
            prepare_results_dir(tmp_path / "out", "run-1")

    def test_empty_directory_is_not_populated(self, tmp_path):
        prepare_results_dir(tmp_path / "out", "run-1")
        assert prepare_results_dir(tmp_path / "out", "run-1").is_dir()

    def test_resume_allows_existing_results(self, tmp_path):
        results = prepare_results_dir(tmp_path / "out", "run-1")
        (results / "jedis.ndjson").write_text("{}\n")
        prepare_results_dir(tmp_path / "out", "run-1", resume=True)
        assert (results / "jedis.ndjson").read_text() == "{}\n"

    def test_overwrite_removes_existing_results(self, tmp_path):
        results = prepare_results_dir(tmp_path / "out", "run-1")
        (results / "jedis.ndjson").write_text("{}\n")
        (results / "_manifest.json").write_text("{}")
        prepare_results_dir(tmp_path / "out", "run-1", overwrite=True)
        assert existing_result_files(results) == []

    def test_latest_link_points_at_the_run(self, tmp_path):
        prepare_results_dir(tmp_path / "out", "run-1")
        link = update_latest_link(tmp_path / "out", "run-1")
        assert link == tmp_path / "out" / "latest"
        assert link.is_symlink()
        assert os.readlink(link) == "run-1"  # relative, survives moving output-dir
        assert link.resolve() == (tmp_path / "out" / "run-1").resolve()

    def test_latest_link_repoints_to_newer_run(self, tmp_path):
        for run_id in ("run-1", "run-2"):
            prepare_results_dir(tmp_path / "out", run_id)
            update_latest_link(tmp_path / "out", run_id)
        assert os.readlink(tmp_path / "out" / "latest") == "run-2"

    def test_latest_link_replaces_a_stale_dangling_link(self, tmp_path):
        out = tmp_path / "out"
        out.mkdir()
        (out / "latest").symlink_to("deleted-run", target_is_directory=True)
        assert not (out / "latest").exists()  # dangling

        prepare_results_dir(out, "run-1")
        update_latest_link(out, "run-1")
        assert (out / "latest").resolve() == (out / "run-1").resolve()

    def test_latest_link_never_clobbers_a_real_directory(self, tmp_path):
        out = tmp_path / "out"
        (out / "latest").mkdir(parents=True)
        (out / "latest" / "keep.ndjson").write_text("{}\n")

        assert update_latest_link(out, "run-1") is None
        assert (out / "latest" / "keep.ndjson").exists()
        assert not (out / "latest").is_symlink()

    @pytest.mark.parametrize("run_id", ["", "a/b", "..", "/abs"])
    def test_rejects_non_segment_run_ids(self, tmp_path, run_id):
        with pytest.raises(ValueError, match="single path segment"):
            validate_run_id(run_id)
        with pytest.raises(PreflightError, match="single path segment"):
            prepare_results_dir(tmp_path / "out", run_id)

    def test_accepts_plain_run_ids(self):
        assert validate_run_id("run-1") == "run-1"


# ═══════════════════════════════════════════════════════════════════════════════
# Manifest
# ═══════════════════════════════════════════════════════════════════════════════

class TestManifestOutcomes:
    def test_manifest_keeps_variants_and_adds_outcomes(self, tmp_path):
        config = {"description": "d", "x_axis": "connections", "iterations": 1}
        combos = [{"label": "jedis", "driver_config": "configs/drivers/default/jedis.json",
                   "params": {"pool_size": 8}, "bindings": {}}]
        cells = [{"label": "jedis", "status": "failed", "error": "boom"}]
        write_manifest(tmp_path, config, combos, run_id="run-1", cells=cells,
                       summary=summarize_cells(cells, 2))

        manifest = json.loads((tmp_path / "_manifest.json").read_text())
        # Shape generate_interactive_graphs.py depends on is unchanged
        assert manifest["variants"]["jedis"]["driver_name"] == "jedis"
        assert manifest["variants"]["jedis"]["params"] == {"pool_size": 8}
        assert manifest["x_axis"] == "connections"
        # New outcome keys
        assert manifest["run_id"] == "run-1"
        assert manifest["summary"] == {"planned": 2, "attempted": 1, "succeeded": 0,
                                       "failed": 1}
        assert manifest["cells"][0]["error"] == "boom"

    def test_manifest_omits_outcome_keys_when_not_supplied(self, tmp_path):
        write_manifest(tmp_path, {"description": "", "x_axis": "connections",
                                  "iterations": 1}, [])
        manifest = json.loads((tmp_path / "_manifest.json").read_text())
        assert "cells" not in manifest and "summary" not in manifest

    def test_graph_generator_still_reads_variants(self, tmp_path):
        from generate_interactive_graphs import load_manifest, assign_variant_colors

        config = {"description": "d", "x_axis": "connections", "iterations": 1}
        combos = [
            {"label": "jedis", "driver_config": "configs/drivers/default/jedis.json",
             "params": {}, "bindings": {}},
            {"label": "lettuce", "driver_config": "configs/drivers/default/lettuce.json",
             "params": {}, "bindings": {}},
        ]
        write_manifest(tmp_path, config, combos, run_id="run-1", cells=[],
                       summary=summarize_cells([], 0))

        manifest = load_manifest(tmp_path)
        assert set(manifest["variants"]) == {"jedis", "lettuce"}
        colors, _dashes, families, display_labels = assign_variant_colors(
            ["jedis", "lettuce"], manifest)
        assert set(colors) == {"jedis", "lettuce"}
        assert set(families) == {"jedis", "lettuce"}
        assert set(display_labels) == {"jedis", "lettuce"}

    def test_summarize_counts(self):
        cells = [{"status": "ok"}, {"status": "failed"}, {"status": "ok"}]
        assert summarize_cells(cells, planned=4) == {
            "planned": 4, "attempted": 3, "succeeded": 2, "failed": 1,
        }


class TestCountNdjsonLines:
    def test_missing_file_is_zero(self, tmp_path):
        assert count_ndjson_lines(tmp_path / "nope.ndjson") == 0
        assert ndjson_size(tmp_path / "nope.ndjson") == 0

    def test_blank_lines_ignored(self, tmp_path):
        path = tmp_path / "m.ndjson"
        path.write_text('{"a":1}\n\n{"a":2}\n')
        assert count_ndjson_lines(path) == 2

    def test_counts_only_lines_appended_after_offset(self, tmp_path):
        path = tmp_path / "m.ndjson"
        path.write_text('{"a":1}\n{"a":2}\n')
        offset = ndjson_size(path)
        with path.open("a") as f:
            f.write('{"a":3}\n')
        assert count_ndjson_lines(path, offset) == 1
        assert count_ndjson_lines(path) == 3

    def test_no_new_records_reads_as_zero(self, tmp_path):
        path = tmp_path / "m.ndjson"
        path.write_text('{"a":1}\n')
        assert count_ndjson_lines(path, ndjson_size(path)) == 0


class TestNonStandaloneModes:
    def test_standalone_only_is_empty(self, tmp_path):
        assert non_standalone_modes(combos_for([make_driver(tmp_path, "jedis")])) == []

    def test_cluster_and_sentinel_are_reported(self, tmp_path):
        cluster = make_driver(tmp_path, "c", mode="cluster")
        sentinel = make_driver(tmp_path, "s", mode="sentinel")
        assert non_standalone_modes(combos_for([cluster, sentinel])) == \
            ["cluster", "sentinel"]

    def test_serverless_driver_mode_is_ignored(self, tmp_path):
        rec = make_driver(tmp_path, "rec", driver_id="recording", mode="cluster")
        assert non_standalone_modes(combos_for([rec])) == []


# ═══════════════════════════════════════════════════════════════════════════════
# run_matrix outcomes (engine stubbed out)
# ═══════════════════════════════════════════════════════════════════════════════

class FakeProc:
    """Stands in for the benchmark subprocess; runs no benchmark."""

    def __init__(self, returncode=0, on_wait=None):
        self.pid = os.getpid()  # so os.getpgid() works for the system monitor
        self.returncode = None
        self._rc = returncode
        self._on_wait = on_wait

    def wait(self):
        if self._on_wait:
            self._on_wait()
        self.returncode = self._rc
        return self._rc


@pytest.fixture
def recording_matrix(tmp_path):
    """Single-cell matrix using a serverless recording driver."""
    driver = make_driver(tmp_path, "recording-stub", driver_id="recording")
    workload = write_json(tmp_path / "workload.json", {
        "phases": [{"id": "STEADY", "connections": 1}],
    })
    matrix = write_json(tmp_path / "matrix.json", {
        "description": "stubbed",
        "x_axis": "connections",
        "iterations": 1,
        "workload_template": str(workload),
        "dimensions": {"connections": [1], "driver_config": [driver]},
    })
    return parse_matrix_config(matrix)


def stub_engine(monkeypatch, returncode=0, records_written=0):
    """Replace the benchmark subprocess with a stub, optionally writing records.

    Only `make <engine>-run` invocations are stubbed; anything else (the CLI used
    by the readiness probe) still runs for real.

    The once-per-sweep engine build (`build_engines`, which shells `make
    <engine>-build`) is stubbed to a no-op here — these tests exercise run_matrix
    outcomes, and the build step is covered separately in test_engine_build.py.
    """
    calls = []
    real_popen = subprocess.Popen

    monkeypatch.setattr("run_benchmark_matrix.build_engines", lambda engines: None)

    def fake_popen(cmd, **kwargs):
        metrics_args = [a for a in cmd if str(a).startswith("METRICS_OUTPUT=")]
        if not metrics_args:
            return real_popen(cmd, **kwargs)
        calls.append(cmd)
        metrics = metrics_args[0].split("=", 1)[1]

        def on_wait():
            with open(metrics, "a") as f:
                for i in range(records_written):
                    f.write(json.dumps({"phase": {"id": "STEADY"}, "n": i}) + "\n")

        return FakeProc(returncode, on_wait)

    monkeypatch.setattr("run_benchmark_matrix.subprocess.Popen", fake_popen)
    return calls


class TestRunMatrixOutcomes:
    def test_successful_cell_exits_zero(self, recording_matrix, tmp_path, monkeypatch):
        calls = stub_engine(monkeypatch, returncode=0, records_written=1)
        summary = run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r")
        assert summary == {"planned": 1, "attempted": 1, "succeeded": 1, "failed": 0}
        assert len(calls) == 1

        manifest = json.loads((tmp_path / "out" / "r" / "_manifest.json").read_text())
        assert manifest["summary"] == {"planned": 1, "attempted": 1, "succeeded": 1,
                                       "failed": 0}
        assert manifest["cells"][0]["status"] == "ok"
        assert manifest["cells"][0]["records_written"] == 1
        assert "duration_seconds" in manifest["cells"][0]

    def test_engine_failure_is_recorded_and_exits_nonzero(self, recording_matrix,
                                                          tmp_path, monkeypatch):
        stub_engine(monkeypatch, returncode=3, records_written=0)
        summary = run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r")
        assert summary["failed"] == 1

        manifest = json.loads((tmp_path / "out" / "r" / "_manifest.json").read_text())
        assert manifest["summary"]["failed"] == 1
        cell = manifest["cells"][0]
        assert cell["status"] == "failed"
        assert "CalledProcessError" in cell["error"]

    def test_exit_zero_without_records_is_a_failure(self, recording_matrix, tmp_path,
                                                    monkeypatch):
        stub_engine(monkeypatch, returncode=0, records_written=0)
        summary = run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r")
        assert summary["failed"] == 1

        manifest = json.loads((tmp_path / "out" / "r" / "_manifest.json").read_text())
        assert "wrote no metrics record" in manifest["cells"][0]["error"]

    def test_recording_driver_never_flushes(self, recording_matrix, tmp_path, monkeypatch):
        stub_engine(monkeypatch, returncode=0, records_written=1)

        def boom(*args, **kwargs):
            raise AssertionError("flush_server must not run for a serverless driver")

        monkeypatch.setattr("run_benchmark_matrix.flush_server", boom)
        assert run_matrix(recording_matrix, tmp_path / "out", "h", 6379,
                          run_id="r")["failed"] == 0

    def test_unreachable_server_fails_before_any_cell(self, tmp_path, monkeypatch):
        driver = make_driver(tmp_path, "jedis")
        workload = write_json(tmp_path / "workload.json",
                              {"phases": [{"id": "STEADY", "connections": 1}]})
        matrix = write_json(tmp_path / "matrix.json", {
            "x_axis": "connections",
            "iterations": 1,
            "workload_template": str(workload),
            "dimensions": {"connections": [1, 4], "driver_config": [driver]},
        })
        config = parse_matrix_config(matrix)

        calls = stub_engine(monkeypatch, returncode=0, records_written=1)
        cli = make_executable(tmp_path / "cli", "#!/bin/sh\nexit 1\n")
        monkeypatch.setattr("run_benchmark_matrix.resolve_cli_path", lambda: str(cli))
        monkeypatch.setattr("run_benchmark_matrix.READINESS_ATTEMPTS", 1)
        monkeypatch.setattr("run_benchmark_matrix.READINESS_DELAY_SECONDS", 0)

        with pytest.raises(PreflightError, match="did not answer PING"):
            run_matrix(config, tmp_path / "out", "h", 6379, run_id="r")

        assert calls == [], "no benchmark should be launched when preflight fails"
        assert not (tmp_path / "out" / "r" / "_manifest.json").exists()
        assert not (tmp_path / "out" / "latest").exists(), \
            "a failed preflight must not publish itself as the latest run"

    def test_flush_failure_costs_only_one_cell(self, tmp_path, monkeypatch):
        driver = make_driver(tmp_path, "jedis")
        workload = write_json(tmp_path / "workload.json",
                              {"phases": [{"id": "STEADY", "connections": 1}]})
        matrix = write_json(tmp_path / "matrix.json", {
            "x_axis": "connections",
            "iterations": 1,
            "workload_template": str(workload),
            "dimensions": {"connections": [1, 4], "driver_config": [driver]},
        })
        config = parse_matrix_config(matrix)

        calls = stub_engine(monkeypatch, returncode=0, records_written=1)
        cli = make_executable(tmp_path / "cli", "#!/bin/sh\necho PONG\n")
        monkeypatch.setattr("run_benchmark_matrix.resolve_cli_path", lambda: str(cli))

        flushes = []

        def flaky_flush(*args, **kwargs):
            flushes.append(args)
            if len(flushes) == 1:
                raise subprocess.CalledProcessError(1, ["flushall"])

        monkeypatch.setattr("run_benchmark_matrix.flush_server", flaky_flush)

        summary = run_matrix(config, tmp_path / "out", "h", 6379, run_id="r")
        assert summary["failed"] == 1
        assert len(flushes) == 2, "the sweep must continue after a failed flush"
        assert len(calls) == 1, "the flushed-failed cell must not run the engine"

        manifest = json.loads((tmp_path / "out" / "r" / "_manifest.json").read_text())
        assert manifest["summary"] == {"planned": 2, "attempted": 2, "succeeded": 1,
                                       "failed": 1}

    def test_empty_matrix_is_a_preflight_error(self, tmp_path, monkeypatch):
        driver = make_driver(tmp_path, "rec", driver_id="recording")
        workload = write_json(tmp_path / "workload.json",
                              {"phases": [{"id": "STEADY", "connections": 1}]})
        matrix = write_json(tmp_path / "matrix.json", {
            "x_axis": "connections",
            "iterations": 1,
            "workload_template": str(workload),
            "dimensions": {"connections": [], "driver_config": [driver]},
        })
        config = parse_matrix_config(matrix)
        with pytest.raises(PreflightError, match="no benchmark cells"):
            run_matrix(config, tmp_path / "out", "h", 6379, run_id="r")

    def test_second_run_into_same_run_id_is_refused(self, recording_matrix, tmp_path,
                                                    monkeypatch):
        stub_engine(monkeypatch, returncode=0, records_written=1)
        assert run_matrix(recording_matrix, tmp_path / "out", "h", 6379,
                          run_id="r")["failed"] == 0
        with pytest.raises(PreflightError, match="already contains results"):
            run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r")

    def test_distinct_run_ids_do_not_merge(self, recording_matrix, tmp_path, monkeypatch):
        stub_engine(monkeypatch, returncode=0, records_written=1)
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r1")
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r2")

        for run_id in ("r1", "r2"):
            metrics = list((tmp_path / "out" / run_id).glob("recording-stub.ndjson"))
            assert len(metrics) == 1
            assert count_ndjson_lines(metrics[0]) == 1

    def test_latest_link_tracks_the_run_just_finished(self, recording_matrix, tmp_path,
                                                     monkeypatch):
        stub_engine(monkeypatch, returncode=0, records_written=1)
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r1")
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r2")

        latest = tmp_path / "out" / "latest"
        assert (latest / "_manifest.json").exists()
        assert json.loads((latest / "_manifest.json").read_text())["run_id"] == "r2"

    def test_resume_repoints_latest_at_the_resumed_run(self, recording_matrix, tmp_path,
                                                      monkeypatch):
        stub_engine(monkeypatch, returncode=0, records_written=1)
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r1")
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r2")
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r1",
                   resume=True)

        assert os.readlink(tmp_path / "out" / "latest") == "r1"

    def test_resume_appends_into_the_same_run(self, recording_matrix, tmp_path,
                                             monkeypatch):
        stub_engine(monkeypatch, returncode=0, records_written=1)
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r")
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r", resume=True)

        metrics = tmp_path / "out" / "r" / "recording-stub.ndjson"
        assert count_ndjson_lines(metrics) == 2
        manifest = json.loads((tmp_path / "out" / "r" / "_manifest.json").read_text())
        assert manifest["resumed"] is True
        assert manifest["summary"]["attempted"] == 1  # this invocation only

    def test_overwrite_starts_the_run_clean(self, recording_matrix, tmp_path, monkeypatch):
        stub_engine(monkeypatch, returncode=0, records_written=1)
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r")
        run_matrix(recording_matrix, tmp_path / "out", "h", 6379, run_id="r",
                   overwrite=True)

        metrics = tmp_path / "out" / "r" / "recording-stub.ndjson"
        assert count_ndjson_lines(metrics) == 1
