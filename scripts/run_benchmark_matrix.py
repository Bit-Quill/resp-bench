#!/usr/bin/env python3
"""
run_benchmark_matrix.py — Matrix-based benchmark orchestrator.

Runs benchmarks across a Cartesian product of configurable dimensions,
producing results that can be visualized with generate_interactive_graphs.py.

Supports arbitrary dimension sweeps:
  - Different drivers in the same run
  - Glide JNI thread configurations (env vars)
  - Pool size sweeps for Spring Data drivers
  - Any combination of the above

Dimensions can be:
  - Arrays: participate in Cartesian product (free dimensions)
  - Bindings ("$other_dim"): mirrors another dimension's value per data point
  - Scalars: fixed value, not varied
  - Objects with "values" + "applies_to": conditional dimensions that only
    apply to matching driver configs (glob matching on driver_config path)

One dimension is designated as the X axis (typically "connections").
All other free dimensions form the series (one line per unique combo).

Output is a flat directory per run, with one NDJSON file per series label:
    <output-dir>/<run-id>/<label>.ndjson
    <output-dir>/<run-id>/<label>.cpu.ndjson
    <output-dir>/<run-id>/_manifest.json
    <output-dir>/latest -> <run-id>       (symlink to the most recent run)

The run id defaults to a UTC timestamp so separate runs never merge into the
same files; pass --run-id to name a run explicitly, and --resume/--overwrite to
deliberately write into a directory that already holds results. The `latest`
symlink lets tooling find the newest run without knowing its id.

The _manifest.json records the full configuration for each variant,
allowing generate_interactive_graphs.py to build rich legends. It also records
the outcome of every cell that was attempted, and the process exits non-zero if
any cell failed.

Usage:
    python scripts/run_benchmark_matrix.py \\
        --matrix configs/matrices/glide-thread-sweep.json \\
        --output-dir results/glide-thread-sweep \\
        --server-host 10.0.0.5

    python scripts/run_benchmark_matrix.py \\
        --matrix configs/matrices/driver-comparison-high-tps.json \\
        --output-dir results/driver-comparison \\
        --server-host localhost
"""

import argparse
import copy
import fnmatch
import itertools
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

from system_monitor import SystemMonitor

# ═══════════════════════════════════════════════════════════════════════════════
# Constants
# ═══════════════════════════════════════════════════════════════════════════════

SYSTEM_MONITOR_INTERVAL = 0.5

# Repo root — scripts/ lives directly under it
REPO_ROOT = Path(__file__).resolve().parent.parent

# Server CLI resolution. SERVER_PROJECT is the same variable the Makefile uses
# (`SERVER_PROJECT?=valkey`), so overriding it moves both the built binaries and
# the path we look them up under. RESP_BENCH_CLI is an explicit escape hatch for
# hosts where the binary lives somewhere else entirely.
CLI_ENV_OVERRIDE = "RESP_BENCH_CLI"
SERVER_PROJECT_ENV = "SERVER_PROJECT"
DEFAULT_SERVER_PROJECT = "valkey"
CLI_TIMEOUT_SECONDS = 10

# Readiness probe: bounded retry before the sweep starts
READINESS_ATTEMPTS = 5
READINESS_DELAY_SECONDS = 1.0

# Files that mark a run directory as already populated
RESULT_PATTERNS = ("*.ndjson", "_manifest.json")

# Symlink inside --output-dir pointing at the most recent run, so tooling (the
# Makefile's benchmark-matrix-graphs target) can find results without a run id.
LATEST_LINK_NAME = "latest"

# Exit codes
EXIT_OK = 0
EXIT_CELL_FAILURES = 1
EXIT_PREFLIGHT = 2

# Well-known dimension names with special handling
DIM_CONNECTIONS = "connections"
DIM_DRIVER_CONFIG = "driver_config"
DIM_POOL_SIZE = "pool_size"
DIM_ENV = "env"

# Dimensions that map to driver config JSON overrides
DRIVER_CONFIG_DIMS = {DIM_POOL_SIZE, "use_pooling", "share_native_connection"}

# Map driver_id → engine make target (for multi-engine support)
DRIVER_ENGINE_MAP = {
    # Java drivers
    "jedis": "java",
    "lettuce": "java",
    "valkey-glide": "java",
    "redisson": "java",
    "spring-data-valkey": "java",
    "spring-data-redis": "java",
    # Ruby drivers
    "redis-rb": "ruby",
    "valkey-glide-ruby": "ruby",
    # C# drivers
    "stackexchange-redis": "csharp",
    "valkey-glide-csharp": "csharp",
    # Recording (default to java)
    "recording": "java",
}

# Driver ids from DRIVER_ENGINE_MAP that generate their own latency and never
# talk to a server: a matrix built only from these needs neither a readiness
# probe nor a FLUSHALL. Keep in sync when adding a synthetic driver above.
SERVERLESS_DRIVER_IDS = {"recording"}


class PreflightError(RuntimeError):
    """A precondition for the whole sweep is not met — nothing was run."""


class CellFailure(RuntimeError):
    """A single matrix cell failed — the sweep continues with the next cell."""


def describe_exception(exc):
    """One-line description of an exception, including captured stderr if any."""
    detail = f"{type(exc).__name__}: {exc}"
    stderr = getattr(exc, "stderr", None)
    if isinstance(stderr, bytes):
        stderr = stderr.decode(errors="replace")
    if stderr and stderr.strip():
        detail += f" — {stderr.strip()}"
    return detail


def read_driver_config(driver_config_path):
    """Read a driver config JSON, returning {} if it cannot be read."""
    try:
        with open(driver_config_path) as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return {}


def config_needs_server(driver_config):
    """True unless the driver generates its own latency without a server.

    Unreadable or unknown configs are assumed to need a server, so a typo in a
    driver path can never silently skip the readiness probe.
    """
    return str(driver_config.get("driver_id", "")).lower() not in SERVERLESS_DRIVER_IDS


def resolve_config_path(path_str):
    """Resolve a path referenced by a matrix config, or None if it does not exist.

    Matrix configs spell `workload_template` and `driver_config` relative to the
    repository root, but the runner may be invoked from anywhere, so try the path
    as written (i.e. relative to the CWD) and then relative to the repo root.

    Both parse_matrix_config()'s validation and the runtime opens go through this
    one function, so a path that validates is by construction a path the run can
    open. Note it returns a resolved path *without* mutating the caller's string:
    the config values double as identity — applies_to globs
    (DimensionSpec.matches_driver) and _manifest.json both consume them — so
    rewriting them would make matrix expansion and manifest contents depend on
    where the repository happens to be checked out.
    """
    as_written = Path(path_str)
    if as_written.is_file():
        return as_written
    if not as_written.is_absolute():
        from_repo_root = REPO_ROOT / as_written
        if from_repo_root.is_file():
            return from_repo_root
    return None


def open_config_path(path_str):
    """Resolve a matrix-referenced config path for reading, or raise."""
    resolved = resolve_config_path(path_str)
    if resolved is None:
        raise FileNotFoundError(
            f"config file referenced by the matrix does not exist: {path_str}"
        )
    return resolved


def detect_engine_for_driver(driver_config_path):
    """Detect the correct engine (make target) for a driver config file.

    Reads the driver_id from the JSON file and maps it to the engine name.
    Falls back to 'java' if unknown.
    """
    try:
        with open(driver_config_path) as f:
            config = json.load(f)
        driver_id = config.get("driver_id", "").lower()
        return DRIVER_ENGINE_MAP.get(driver_id, "java")
    except (json.JSONDecodeError, OSError, KeyError):
        return "java"


# ═══════════════════════════════════════════════════════════════════════════════
# Matrix Config Parsing
# ═══════════════════════════════════════════════════════════════════════════════

class DimensionSpec:
    """Parsed specification for a single dimension."""

    def __init__(self, name, raw_value):
        self.name = name
        self.raw = raw_value

        if isinstance(raw_value, dict) and "values" in raw_value:
            # Extended form: {"values": [...], "applies_to": {...}}
            self.values = raw_value["values"]
            self.applies_to = raw_value.get("applies_to", None)
        elif isinstance(raw_value, list):
            # Simple array
            self.values = raw_value
            self.applies_to = None
        elif isinstance(raw_value, str) and raw_value.startswith("$"):
            # Binding reference
            self.values = [raw_value]
            self.applies_to = None
        else:
            # Scalar (fixed value)
            self.values = [raw_value]
            self.applies_to = None

    @property
    def is_binding_only(self):
        """True if ALL values are bindings (e.g., ["$connections"])."""
        return all(isinstance(v, str) and v.startswith("$") for v in self.values)

    @property
    def is_scalar(self):
        """True if this is a single fixed value (not a binding)."""
        return len(self.values) == 1 and not self.is_binding_only

    def matches_driver(self, driver_config_path):
        """Check if this dimension applies to the given driver config path."""
        if self.applies_to is None:
            return True  # No filter = applies to all

        for filter_dim, patterns in self.applies_to.items():
            if filter_dim == DIM_DRIVER_CONFIG:
                basename = Path(driver_config_path).stem
                full_path = str(driver_config_path)
                if not any(
                    fnmatch.fnmatch(basename, p) or fnmatch.fnmatch(full_path, p)
                    for p in patterns
                ):
                    return False
        return True


def parse_matrix_config(matrix_path):
    """Parse and validate a matrix configuration JSON file.

    Returns:
        config: dict with keys:
            - description: str
            - x_axis: str (dimension name)
            - iterations: int
            - server_host: str (optional)
            - port: int (optional)
            - workload_template: str (optional)
            - dimensions: dict[name] -> DimensionSpec
    """
    with open(matrix_path) as f:
        raw = json.load(f)

    config = {
        "description": raw.get("description", ""),
        "x_axis": raw.get("x_axis", DIM_CONNECTIONS),
        "iterations": raw.get("iterations", 10),
        "server_host": raw.get("server_host", None),
        "port": raw.get("port", 6379),
        "workload_template": raw.get("workload_template"),
        "cpu_interval": raw.get("cpu_interval", SYSTEM_MONITOR_INTERVAL),
    }

    dimensions = {}
    for name, spec in raw.get("dimensions", {}).items():
        dimensions[name] = DimensionSpec(name, spec)

    config["dimensions"] = dimensions

    # Validation
    x_axis = config["x_axis"]
    if x_axis not in dimensions:
        raise ValueError(f"x_axis '{x_axis}' not found in dimensions: {list(dimensions.keys())}")

    if DIM_DRIVER_CONFIG not in dimensions:
        raise ValueError(f"'{DIM_DRIVER_CONFIG}' dimension is required")

    if not config["workload_template"]:
        raise ValueError("'workload_template' is required in matrix config")

    # Referenced config files must exist. Without this a typo'd path surfaces
    # only mid-run — after the server is up and the engine has been built —
    # because --dry-run never opens these files. Checking them through the same
    # resolve_config_path() that generate_workload() and generate_driver_config()
    # use means anything accepted here is openable by the run itself.
    missing = []

    workload_template = config["workload_template"]
    if not isinstance(workload_template, str) or resolve_config_path(workload_template) is None:
        missing.append(f"workload_template: {workload_template!r}")

    for value in dimensions[DIM_DRIVER_CONFIG].values:
        # Every value is checked, including "$binding" strings. Unlike other
        # dimensions, driver_config is never a binding target —
        # generate_series_combos() excludes it from the series dimensions, so
        # resolve_binding() never rewrites it and a "$foo" here would reach
        # open() verbatim.
        if not isinstance(value, str) or resolve_config_path(value) is None:
            missing.append(f"{DIM_DRIVER_CONFIG}: {value!r}")

    if missing:
        raise ValueError(
            f"matrix config '{matrix_path}' references files that do not exist:\n  "
            + "\n  ".join(missing)
        )

    return config


# ═══════════════════════════════════════════════════════════════════════════════
# Cartesian Product with Filtering and Binding Resolution
# ═══════════════════════════════════════════════════════════════════════════════

def resolve_binding(value, resolved_values):
    """Resolve a binding reference like '$connections' to its current value."""
    if isinstance(value, str) and value.startswith("$"):
        ref_dim = value[1:]
        if ref_dim in resolved_values:
            return resolved_values[ref_dim]
        raise ValueError(f"Binding '{value}' references unknown dimension '{ref_dim}'")
    return value


def generate_series_combos(config):
    """Generate all series combinations (non-x-axis dimension combos).

    For each driver_config, filters dimensions by applies_to and computes
    the Cartesian product of applicable dimensions.

    Returns list of dicts, each representing one series:
        {
            "label": "sdv-glide_pool8_threads16x16",
            "driver_config": "configs/drivers/...",
            "params": {"pool_size": 8, "env": {...}, ...},
            "bindings": {"pool_size": "$connections", ...}  # if any
        }
    """
    dims = config["dimensions"]
    x_axis = config["x_axis"]

    # Get driver configs
    driver_dim = dims[DIM_DRIVER_CONFIG]
    driver_configs = driver_dim.values

    # Identify series dimensions (everything except x_axis and driver_config)
    series_dim_names = [
        name for name in dims
        if name != x_axis and name != DIM_DRIVER_CONFIG
    ]

    all_combos = []

    for driver_cfg in driver_configs:
        driver_label = Path(driver_cfg).stem

        # Filter series dimensions by applies_to
        applicable_dims = []
        for dim_name in series_dim_names:
            dim_spec = dims[dim_name]
            if dim_spec.matches_driver(driver_cfg):
                applicable_dims.append(dim_spec)

        # Separate free dimensions (array values) from pure bindings
        free_dims = []
        binding_dims = []
        for dim_spec in applicable_dims:
            # Check if ALL values are bindings
            if dim_spec.is_binding_only:
                binding_dims.append(dim_spec)
            else:
                # Separate concrete values from binding values
                concrete_vals = []
                binding_vals = []
                for v in dim_spec.values:
                    if isinstance(v, str) and v.startswith("$"):
                        binding_vals.append(v)
                    else:
                        concrete_vals.append(v)
                if concrete_vals or binding_vals:
                    free_dims.append((dim_spec.name, concrete_vals + binding_vals))

        if not free_dims:
            # No series dimensions for this driver — single series
            all_combos.append({
                "label": driver_label,
                "driver_config": driver_cfg,
                "params": {},
                "bindings": {d.name: d.values[0] for d in binding_dims},
            })
            continue

        # Cartesian product of free dimensions
        dim_names = [name for name, _ in free_dims]
        dim_values = [vals for _, vals in free_dims]

        for combo in itertools.product(*dim_values):
            params = {}
            bindings = {}

            for name, value in zip(dim_names, combo):
                if isinstance(value, str) and value.startswith("$"):
                    bindings[name] = value
                else:
                    params[name] = value

            # Add pure binding dims
            for dim_spec in binding_dims:
                bindings[dim_spec.name] = dim_spec.values[0]

            # Build label: driver_name@param1=val,param2=val
            # The @ separator cleanly delimits driver name from params
            param_parts = []
            for name, value in sorted(params.items()):
                if isinstance(value, dict):
                    # For env dicts, create compact key=value pairs
                    for k, v in sorted(value.items()):
                        short_key = k.replace("GLIDE_TOKIO_WORKER_THREADS", "tw") \
                                     .replace("GLIDE_CALLBACK_WORKER_THREADS", "cb")
                        param_parts.append(f"{short_key}={v}")
                else:
                    param_parts.append(f"{name}={value}")

            for name, binding in sorted(bindings.items()):
                ref = binding[1:]  # strip $
                param_parts.append(f"{name}={ref}")

            label = driver_label if not param_parts else f"{driver_label}@{','.join(param_parts)}"

            all_combos.append({
                "label": label,
                "driver_config": driver_cfg,
                "params": params,
                "bindings": bindings,
            })

    return all_combos


# ═══════════════════════════════════════════════════════════════════════════════
# Workload & Driver Config Generation
# ═══════════════════════════════════════════════════════════════════════════════

def generate_workload(template_path, connections):
    """Generate a workload JSON with the given connection count."""
    with open(open_config_path(template_path)) as f:
        workload = json.load(f)

    for phase in workload.get("phases", []):
        phase["connections"] = connections

    return workload


def generate_driver_config(base_config_path, overrides):
    """Generate a modified driver config JSON with overrides applied.

    overrides is a dict of specific_driver_config keys to set,
    e.g. {"pool_size": 32, "use_pooling": true}.
    """
    with open(open_config_path(base_config_path)) as f:
        config = json.load(f)

    if overrides:
        sdc = config.get("specific_driver_config", {})
        sdc.update(overrides)
        config["specific_driver_config"] = sdc

    return config


# ═══════════════════════════════════════════════════════════════════════════════
# Benchmark Execution
# ═══════════════════════════════════════════════════════════════════════════════

def resolve_cli_path(env=None, which=shutil.which, repo_root=REPO_ROOT):
    """Resolve the CLI binary used for the readiness probe and FLUSHALL.

    Resolution order:
      1. $RESP_BENCH_CLI, used verbatim (explicit override).
      2. work/<project>/bin/<project>-cli under the repo root — the binary the
         Makefile builds — where <project> is $SERVER_PROJECT (default 'valkey').
      3. <project>-cli on PATH.
      4. valkey-cli, then redis-cli, on PATH.

    Returns the resolved path as a string, or None if nothing usable was found.
    """
    env = os.environ if env is None else env

    override = env.get(CLI_ENV_OVERRIDE)
    if override:
        return override

    project = env.get(SERVER_PROJECT_ENV) or DEFAULT_SERVER_PROJECT
    built = Path(repo_root) / "work" / project / "bin" / f"{project}-cli"
    if built.is_file() and os.access(built, os.X_OK):
        return str(built)

    for name in dict.fromkeys([f"{project}-cli", "valkey-cli", "redis-cli"]):
        found = which(name)
        if found:
            return found

    return None


def cli_connection_args(server_host, port, auth=None, tls=None):
    """Build the connection flags for the CLI from a driver config's auth/tls."""
    args = ["-h", str(server_host), "-p", str(port)]

    auth = auth or {}
    password = auth.get("password")
    if password:
        # --user needs -a, so username is only meaningful alongside a password.
        args += ["--no-auth-warning", "-a", str(password)]
        username = auth.get("username")
        if username:
            args += ["--user", str(username)]

    tls = tls or {}
    if tls.get("enabled"):
        args.append("--tls")
        for key, flag in (("ca_path", "--cacert"), ("cert_path", "--cert"), ("key_path", "--key")):
            value = tls.get(key)
            if value:
                args += [flag, str(value)]
        if not tls.get("verify_hostname", True):
            args.append("--insecure")

    return args


def flush_server(cli_path, server_host, port, auth=None, tls=None):
    """Run FLUSHALL on the server via the resolved CLI binary."""
    subprocess.run(
        [cli_path] + cli_connection_args(server_host, port, auth, tls) + ["flushall"],
        check=True,
        capture_output=True,
        timeout=CLI_TIMEOUT_SECONDS,
    )


def probe_server(cli_path, server_host, port, auth=None, tls=None,
                 attempts=READINESS_ATTEMPTS, delay=READINESS_DELAY_SECONDS,
                 sleep=time.sleep):
    """PING the server with bounded retry.

    Returns (ok, detail) where detail is the reply on success and the last
    error seen on failure.
    """
    cmd = [cli_path] + cli_connection_args(server_host, port, auth, tls) + ["ping"]
    attempts = max(1, attempts)
    detail = "no attempt made"

    for attempt in range(1, attempts + 1):
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True,
                                  timeout=CLI_TIMEOUT_SECONDS)
            stdout = (proc.stdout or "").strip()
            stderr = (proc.stderr or "").strip()
            if proc.returncode == 0 and "PONG" in stdout.upper():
                return True, stdout
            detail = stderr or stdout or f"exit code {proc.returncode}"
        except (OSError, subprocess.SubprocessError) as e:
            detail = str(e)

        if attempt < attempts:
            print(f"  server not ready (attempt {attempt}/{attempts}): {detail}",
                  file=sys.stderr)
            sleep(delay)

    return False, detail


def driver_server_settings(series_combos):
    """Map each driver config path to its (auth, tls) pair, or None if serverless."""
    settings = {}
    for combo in series_combos:
        path = combo["driver_config"]
        if path in settings:
            continue
        cfg = read_driver_config(path)
        if not config_needs_server(cfg):
            settings[path] = None
            continue
        settings[path] = (cfg.get("auth") or {}, cfg.get("tls") or {})
    return settings


def non_standalone_modes(series_combos):
    """Modes other than 'standalone' used by drivers that need a server.

    The probe and FLUSHALL address one endpoint, which is all a standalone
    server has. Cluster and sentinel matrices get a warning rather than a hard
    failure, because a single-endpoint flush is what the runner always did.
    """
    modes = set()
    for combo in series_combos:
        cfg = read_driver_config(combo["driver_config"])
        mode = str(cfg.get("mode", "standalone")).lower()
        if config_needs_server(cfg) and mode != "standalone":
            modes.add(mode)
    return sorted(modes)


def unique_server_credentials(settings):
    """Distinct (auth, tls) pairs among the driver configs that need a server."""
    unique, seen = [], set()
    for value in settings.values():
        if value is None:
            continue
        key = json.dumps(value, sort_keys=True, default=str)
        if key not in seen:
            seen.add(key)
            unique.append(value)
    return unique


def preflight_server(settings, server_host, port, cli_path=None,
                     attempts=READINESS_ATTEMPTS, delay=READINESS_DELAY_SECONDS):
    """Resolve the CLI and probe the server before any benchmark work starts.

    Returns the CLI path to use for per-cell flushes, or None when no driver in
    this matrix needs a server (probe and flush are both skipped then).

    Raises PreflightError if a server is needed but no CLI can be found or the
    endpoint never answers PING — so the sweep fails up front instead of dying
    on cell #1.
    """
    credentials = unique_server_credentials(settings)
    if not credentials:
        print("Preflight: no driver in this matrix needs a server "
              "— skipping readiness probe and FLUSHALL")
        return None

    cli_path = cli_path or resolve_cli_path()
    if cli_path is None:
        project = os.environ.get(SERVER_PROJECT_ENV) or DEFAULT_SERVER_PROJECT
        raise PreflightError(
            f"no {project}-cli binary found. Looked for "
            f"${CLI_ENV_OVERRIDE}, work/{project}/bin/{project}-cli (built by the "
            f"Makefile's server targets), and {project}-cli/valkey-cli/redis-cli on "
            f"PATH. Build the server binaries, install a CLI, or set "
            f"${CLI_ENV_OVERRIDE} to its path."
        )
    print(f"Preflight: using CLI {cli_path}")

    attempts = max(1, attempts)
    for auth, tls in credentials:
        label = f"{server_host}:{port}" + (" (tls)" if tls.get("enabled") else "")
        ok, detail = probe_server(cli_path, server_host, port, auth, tls,
                                  attempts=attempts, delay=delay)
        if not ok:
            raise PreflightError(
                f"server at {label} did not answer PING after {attempts} "
                f"attempt(s): {detail}"
            )
        print(f"Preflight: server ready at {label} ({detail})")

    return cli_path


def ndjson_size(path):
    """Byte size of an append-only NDJSON file (0 if it does not exist)."""
    path = Path(path)
    return path.stat().st_size if path.exists() else 0


def count_ndjson_lines(path, offset=0):
    """Count non-blank NDJSON lines written after `offset` bytes.

    Lines are counted rather than phase records: the schema may grow other
    record kinds (e.g. interval reports), and any line at all proves the engine
    produced output. Metrics files are append-only, so starting from the size
    captured before a cell keeps this proportional to what that cell wrote.
    """
    path = Path(path)
    if not path.exists():
        return 0
    with path.open() as f:
        f.seek(offset)
        return sum(1 for line in f if line.strip())


def run_benchmark(server, driver_file, workload_file, metrics_output, env_overrides=None):
    """Run a single benchmark via `make java-run`."""
    env = os.environ.copy()
    if env_overrides:
        env.update({k: str(v) for k, v in env_overrides.items()})

    subprocess.run(
        [
            "make", "java-run",
            f"SERVER={server}",
            f"DRIVER={driver_file}",
            f"WORKLOAD={workload_file}",
            f"METRICS_OUTPUT={metrics_output}",
        ],
        check=True,
        env=env,
    )


# ═══════════════════════════════════════════════════════════════════════════════
# Manifest
# ═══════════════════════════════════════════════════════════════════════════════

def default_run_id():
    """A UTC timestamp run id, e.g. 20260321T140322Z."""
    return time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())


def utc_timestamp():
    """UTC timestamp in the same ISO form SystemMonitor writes, for correlation."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def existing_result_files(results_dir):
    """Result files already present in a run directory."""
    results_dir = Path(results_dir)
    if not results_dir.is_dir():
        return []
    found = []
    for pattern in RESULT_PATTERNS:
        found.extend(sorted(results_dir.glob(pattern)))
    return found


def validate_run_id(run_id):
    """Return run_id if it names a single directory, else raise ValueError."""
    if not run_id or Path(run_id).name != run_id or run_id in (os.curdir, os.pardir):
        raise ValueError(f"run id must be a single path segment, got {run_id!r}")
    return run_id


def prepare_results_dir(output_dir, run_id, resume=False, overwrite=False):
    """Create <output-dir>/<run-id>/ and guard against silently merging runs.

    Raises PreflightError if the directory already holds results and neither
    resume nor overwrite was requested, so appending onto a previous run is
    always a deliberate choice.
    """
    try:
        validate_run_id(run_id)
    except ValueError as e:
        raise PreflightError(str(e)) from e

    results_dir = Path(output_dir) / run_id
    existing = existing_result_files(results_dir)
    if existing:
        if overwrite:
            print(f"Overwriting {len(existing)} existing result file(s) in {results_dir}")
            for path in existing:
                path.unlink()
        elif not resume:
            names = ", ".join(p.name for p in existing[:5])
            if len(existing) > 5:
                names += f", … (+{len(existing) - 5} more)"
            raise PreflightError(
                f"{results_dir} already contains results ({names}). Metrics are "
                f"appended, so writing here would merge two runs into one file. "
                f"Use a new --run-id (the default is a UTC timestamp), --resume to "
                f"append deliberately, or --overwrite to discard them."
            )

    results_dir.mkdir(parents=True, exist_ok=True)
    return results_dir


def failed_cells(cells):
    """The cell records that did not complete successfully."""
    return [c for c in cells if c.get("status") != "ok"]


def update_latest_link(output_dir, run_id):
    """Point <output-dir>/latest at this run's directory.

    Called once the run is committed to starting, so a failed preflight leaves
    the link on the previous good run. A --resume run repoints it at the run it
    appended to, which is still the newest data.

    Returns the link path, or None if the name is already taken by something
    that is not a symlink — real files are never clobbered.
    """
    link = Path(output_dir) / LATEST_LINK_NAME
    if link.exists() and not link.is_symlink():
        print(f"WARNING: {link} is not a symlink, leaving it alone — point tooling "
              f"at the run directory explicitly", file=sys.stderr)
        return None

    # Relative target, so the tree stays valid if --output-dir is moved.
    staging = link.with_name(f".{LATEST_LINK_NAME}.tmp")
    staging.unlink(missing_ok=True)
    staging.symlink_to(run_id, target_is_directory=True)
    os.replace(staging, link)  # atomic, also replaces a stale/dangling link
    return link


def summarize_cells(cells, planned):
    """Count planned / attempted / succeeded / failed cells."""
    failed = failed_cells(cells)
    return {
        "planned": planned,
        "attempted": len(cells),
        "succeeded": len(cells) - len(failed),
        "failed": len(failed),
    }


def write_manifest(output_dir, config, series_combos, run_id=None, cells=None,
                   summary=None, resumed=False):
    """Write _manifest.json describing the matrix run.

    `variants` keeps the shape generate_interactive_graphs.py reads for legend
    labels. `cells` and `summary` describe what actually ran, and cover this
    invocation only (a --resume run does not restate the earlier attempt).
    """
    variants = {}
    for combo in series_combos:
        variant_info = {
            "driver_config": combo["driver_config"],
            "driver_name": Path(combo["driver_config"]).stem,
        }
        if combo["params"]:
            variant_info["params"] = combo["params"]
        if combo["bindings"]:
            variant_info["bindings"] = combo["bindings"]
        variants[combo["label"]] = variant_info

    manifest = {
        "description": config["description"],
        "x_axis": config["x_axis"],
        "iterations": config["iterations"],
        "variants": variants,
    }
    if run_id is not None:
        manifest["run_id"] = run_id
    if resumed:
        manifest["resumed"] = True
    if summary is not None:
        manifest["summary"] = summary
    if cells is not None:
        manifest["cells"] = cells

    manifest_path = Path(output_dir) / "_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Wrote manifest: {manifest_path}")


# ═══════════════════════════════════════════════════════════════════════════════
# Main Orchestration Loop
# ═══════════════════════════════════════════════════════════════════════════════

def run_matrix(config, output_dir, server_host, port, run_id=None, resume=False,
               overwrite=False):
    """Main orchestration loop: for each iteration × x_value × series_combo, run benchmark.

    Returns the run summary (planned / attempted / succeeded / failed counts);
    main() maps that to a process exit code. Raises PreflightError when the sweep
    could not be started at all.
    """
    dims = config["dimensions"]
    x_axis = config["x_axis"]
    x_values = dims[x_axis].values
    iterations = config["iterations"]
    cpu_interval = config["cpu_interval"]
    workload_template = config["workload_template"]

    server = f"{server_host}:{port}"

    # Generate series combos
    series_combos = generate_series_combos(config)

    total_cells = len(x_values) * len(series_combos) * iterations
    if total_cells <= 0:
        raise PreflightError(
            f"matrix produced no benchmark cells "
            f"({x_axis}={len(x_values)} value(s) × {len(series_combos)} series × "
            f"{iterations} iteration(s))"
        )

    run_id = run_id or default_run_id()

    print("=" * 70)
    print(f"Matrix Benchmark Run")
    print(f"  Description: {config['description']}")
    print(f"  Server:      {server}")
    print(f"  Run id:      {run_id}")
    print(f"  X axis:      {x_axis} = {x_values}")
    print(f"  Series:      {len(series_combos)}")
    for combo in series_combos:
        print(f"    - {combo['label']}")
        if combo['params']:
            print(f"      params: {combo['params']}")
        if combo['bindings']:
            print(f"      bindings: {combo['bindings']}")
    print(f"  Iterations:  {iterations}")
    print(f"  Total runs:  {total_cells}")
    print("=" * 70)

    # Preflight — refuse to start rather than dying part-way through the sweep
    results_dir = prepare_results_dir(output_dir, run_id, resume=resume,
                                      overwrite=overwrite)
    print(f"Preflight: results dir {results_dir}")
    server_settings = driver_server_settings(series_combos)
    cli_path = preflight_server(server_settings, server_host, port)
    for mode in non_standalone_modes(series_combos):
        print(f"WARNING: driver mode '{mode}' — the readiness probe and FLUSHALL only "
              f"cover {server}, not the other nodes of the topology", file=sys.stderr)

    # Preflight passed — publish this run as the latest one
    latest_link = update_latest_link(output_dir, run_id)
    if latest_link:
        print(f"Preflight: {latest_link} -> {run_id}")

    # Write manifest
    write_manifest(results_dir, config, series_combos, run_id=run_id, resumed=resume)

    # Create temp dir for generated configs
    cells = []
    tmpdir = Path(tempfile.mkdtemp(prefix="resp-bench-matrix-"))

    try:
        for iteration in range(1, iterations + 1):
            for x_val in x_values:
                for combo in series_combos:
                    label = combo["label"]
                    driver_cfg_path = combo["driver_config"]
                    params = dict(combo["params"])
                    bindings = combo["bindings"]

                    # Resolve bindings for this x_value
                    resolved = {x_axis: x_val}
                    resolved.update(params)
                    for dim_name, binding in bindings.items():
                        resolved[dim_name] = resolve_binding(binding, resolved)
                        params[dim_name] = resolved[dim_name]

                    print(f"\n=== iter={iteration}  {x_axis}={x_val}  "
                          f"series={label} ===")

                    # Generate workload
                    if x_axis == DIM_CONNECTIONS:
                        connections = x_val
                    else:
                        connections = resolved.get(DIM_CONNECTIONS, 1)

                    workload = generate_workload(workload_template, connections)
                    workload_path = tmpdir / f"workload_{label}_{x_val}.json"
                    workload_path.write_text(json.dumps(workload, indent=2))

                    # Generate driver config with overrides
                    driver_overrides = {}
                    for dim_name, dim_val in resolved.items():
                        if dim_name in DRIVER_CONFIG_DIMS:
                            driver_overrides[dim_name] = dim_val

                    driver_config = generate_driver_config(driver_cfg_path, driver_overrides)
                    driver_path = tmpdir / f"driver_{label}_{x_val}.json"
                    driver_path.write_text(json.dumps(driver_config, indent=2))

                    # Collect env overrides
                    env_overrides = {}
                    env_val = resolved.get(DIM_ENV)
                    if env_val and isinstance(env_val, dict):
                        env_overrides.update(env_val)
                    elif DIM_ENV in params and isinstance(params[DIM_ENV], dict):
                        env_overrides.update(params[DIM_ENV])

                    # Output paths
                    metrics_output = str(results_dir / f"{label}.ndjson")
                    system_output = str(results_dir / f"{label}.system.ndjson")

                    # Prepare benchmark command
                    bench_env = os.environ.copy()
                    if env_overrides:
                        bench_env.update({k: str(v) for k, v in env_overrides.items()})

                    # Auto-detect engine from driver config
                    engine = detect_engine_for_driver(str(driver_path))
                    bench_cmd = [
                        "make", f"{engine}-run",
                        f"SERVER={server}",
                        f"DRIVER={str(driver_path)}",
                        f"WORKLOAD={str(workload_path)}",
                        f"METRICS_OUTPUT={metrics_output}",
                    ]

                    cell = {
                        "iteration": iteration,
                        "x_value": x_val,
                        "label": label,
                        "engine": engine,
                        "metrics_output": Path(metrics_output).name,
                        "started_at": utc_timestamp(),
                        "status": "ok",
                    }
                    metrics_offset = ndjson_size(metrics_output)
                    cell_started = time.monotonic()

                    try:
                        # Flush the server inside the guarded region: a transient
                        # flush failure costs this cell, not the whole sweep.
                        settings = server_settings.get(driver_cfg_path)
                        if cli_path is not None and settings is not None:
                            flush_server(cli_path, server_host, port, *settings)

                        # Launch benchmark as subprocess, get its PGID for memory tracking
                        bench_proc = subprocess.Popen(
                            bench_cmd,
                            env=bench_env,
                            start_new_session=True,  # new process group
                        )
                        pgid = os.getpgid(bench_proc.pid)

                        # Start system monitor with process group tracking
                        with SystemMonitor(system_output, interval=cpu_interval, target_pgid=pgid):
                            bench_proc.wait()

                        if bench_proc.returncode != 0:
                            raise subprocess.CalledProcessError(bench_proc.returncode, bench_cmd)

                        # An engine can exit 0 and write nothing, so check that a
                        # record actually landed instead of trusting the exit code.
                        cell["records_written"] = count_ndjson_lines(metrics_output,
                                                                    metrics_offset)
                        if cell["records_written"] <= 0:
                            raise CellFailure(
                                f"engine exited 0 but wrote no metrics record to "
                                f"{metrics_output}"
                            )

                    except (subprocess.SubprocessError, OSError, CellFailure) as e:
                        cell["status"] = "failed"
                        cell["error"] = describe_exception(e)
                        print(f"ERROR: Benchmark failed for {label} with "
                              f"{x_axis}={x_val} (iter {iteration}): {cell['error']}",
                              file=sys.stderr)
                    finally:
                        cell["duration_seconds"] = round(time.monotonic() - cell_started, 3)
                        cells.append(cell)

    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)
        # Rewrite the manifest even if the sweep aborted, so the recorded
        # outcomes always match what was actually attempted.
        summary = summarize_cells(cells, total_cells)
        write_manifest(results_dir, config, series_combos, run_id=run_id, cells=cells,
                       summary=summary, resumed=resume)

    failed = failed_cells(cells)

    print("\n" + "=" * 70)
    if failed:
        print(f"Matrix benchmark FAILED: {summary['failed']} of "
              f"{summary['attempted']} cell(s) failed "
              f"({summary['succeeded']} succeeded, {summary['planned']} planned)")
        for cell in failed:
            print(f"  - iter={cell['iteration']} {x_axis}={cell['x_value']} "
                  f"series={cell['label']}: {cell['error']}", file=sys.stderr)
    else:
        print(f"Matrix benchmark completed: {summary['succeeded']} of "
              f"{summary['planned']} cell(s) succeeded, 0 failed")
    print(f"Results in: {results_dir}")
    print(f"Generate graphs with:")
    print(f"  python scripts/generate_interactive_graphs.py {results_dir}")
    print("=" * 70)

    return summary


# ═══════════════════════════════════════════════════════════════════════════════
# CLI
# ═══════════════════════════════════════════════════════════════════════════════

def _run_id_arg(value):
    """argparse adapter around validate_run_id, so typos read as usage errors."""
    try:
        return validate_run_id(value)
    except ValueError as e:
        raise argparse.ArgumentTypeError(str(e)) from e


def parse_args():
    parser = argparse.ArgumentParser(
        description="Run benchmarks across a configurable matrix of dimensions",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Glide thread configuration sweep
  python scripts/run_benchmark_matrix.py \\
      --matrix configs/matrices/valkey-glide-thread-sweep.json \\
      --output-dir results/valkey-glide-thread-sweep \\
      --server-host 10.0.0.5

  # Compare all drivers with default configs
  python scripts/run_benchmark_matrix.py \\
      --matrix configs/matrices/driver-comparison-high-tps.json \\
      --output-dir results/driver-comparison \\
      --server-host localhost

  # Dry run to see what would be executed
  python scripts/run_benchmark_matrix.py \\
      --matrix configs/matrices/valkey-glide-thread-sweep.json \\
      --output-dir /tmp/test \\
      --dry-run

Exit codes:
  0  every attempted cell succeeded
  1  at least one cell failed (the sweep still ran to the end)
  2  preflight failed — nothing was run (server unreachable, no CLI binary,
     populated run directory, or a matrix with no cells)
""",
    )
    parser.add_argument(
        "--matrix", "-m",
        required=True,
        help="Path to matrix configuration JSON file",
    )
    parser.add_argument(
        "--output-dir", "-o",
        required=True,
        help="Base directory for benchmark results; results land in <output-dir>/<run-id>/",
    )
    parser.add_argument(
        "--run-id",
        default=None,
        type=_run_id_arg,
        help="Name of this run's subdirectory under --output-dir "
             "(default: UTC timestamp, e.g. 20260321T140322Z)",
    )
    populated = parser.add_mutually_exclusive_group()
    populated.add_argument(
        "--resume",
        action="store_true",
        help="Append into a run directory that already contains results "
             "(does not skip cells an earlier attempt already completed)",
    )
    populated.add_argument(
        "--overwrite",
        action="store_true",
        help="Delete existing results in the run directory before writing",
    )
    parser.add_argument(
        "--server-host",
        default=None,
        help="Hostname/IP of the Valkey/Redis server (overrides matrix config, default: localhost)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help="Port of the Valkey/Redis server (overrides matrix config, default: 6379)",
    )
    parser.add_argument(
        "--iterations",
        type=int,
        default=None,
        help="Override iterations from matrix config",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show what would be executed without actually running benchmarks",
    )
    return parser.parse_args()


def main():
    args = parse_args()

    config = parse_matrix_config(args.matrix)

    # CLI overrides
    server_host = args.server_host or config.get("server_host") or "localhost"
    port = args.port or config.get("port", 6379)
    if args.iterations:
        config["iterations"] = args.iterations

    output_dir = Path(args.output_dir)

    if args.dry_run:
        series_combos = generate_series_combos(config)
        dims = config["dimensions"]
        x_axis = config["x_axis"]
        x_values = dims[x_axis].values

        print("=" * 70)
        print("DRY RUN — Matrix Benchmark Plan")
        print(f"  Description: {config['description']}")
        print(f"  Server:      {server_host}:{port}")
        print(f"  X axis:      {x_axis} = {x_values}")
        print(f"  Series:      {len(series_combos)}")
        for combo in series_combos:
            print(f"\n  Series: {combo['label']}")
            print(f"    driver_config: {combo['driver_config']}")
            if combo['params']:
                print(f"    params: {json.dumps(combo['params'], indent=6)}")
            if combo['bindings']:
                print(f"    bindings: {combo['bindings']}")
        run_id = args.run_id or default_run_id()
        server_settings = driver_server_settings(series_combos)
        needs_server = any(v is not None for v in server_settings.values())
        total_cells = len(x_values) * len(series_combos) * config["iterations"]

        print(f"\n  Iterations:  {config['iterations']}")
        print(f"  Total runs:  {total_cells}")
        if total_cells <= 0:
            print("  WARNING: this matrix has no cells — a real run would exit "
                  f"{EXIT_PREFLIGHT} without running anything", file=sys.stderr)
        print(f"  Output:      {output_dir / run_id}")
        print(f"  Server needed: {needs_server}")
        if needs_server:
            print(f"  CLI (flush/probe): {resolve_cli_path() or 'NOT FOUND'}")

        # Show per-x-value resolution for all series with bindings
        for combo in series_combos:
            if combo["bindings"]:
                print(f"\n  Binding resolution for '{combo['label']}':")
                for x_val in x_values:
                    resolved = {x_axis: x_val}
                    resolved.update(combo["params"])
                    for dim_name, binding in combo["bindings"].items():
                        resolved[dim_name] = resolve_binding(binding, resolved)
                    print(f"    {x_axis}={x_val} → {dict((k, v) for k, v in resolved.items() if k != x_axis)}")

        print("\n" + "=" * 70)
        return

    try:
        summary = run_matrix(
            config, output_dir, server_host, port,
            run_id=args.run_id, resume=args.resume, overwrite=args.overwrite,
        )
    except PreflightError as e:
        print(f"\nERROR: preflight failed, no benchmarks were run: {e}", file=sys.stderr)
        sys.exit(EXIT_PREFLIGHT)

    sys.exit(EXIT_CELL_FAILURES if summary["failed"] else EXIT_OK)


if __name__ == "__main__":
    main()
