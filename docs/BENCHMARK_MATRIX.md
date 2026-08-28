# Benchmark Matrix Orchestrator

`run_benchmark_matrix.py` runs benchmarks across a Cartesian product of configurable dimensions — different drivers, thread configurations, pool sizes, environment variables, or any combination — producing results in a flat directory that can be visualized with `generate_interactive_graphs.py`.

The matrix orchestrator supports arbitrary multi-dimensional parameter sweeps.

## Quick Start

```bash
# Dry run — see what would be executed without running anything
python scripts/run_benchmark_matrix.py \
    --matrix configs/matrices/valkey-glide-thread-sweep.json \
    --output-dir results/valkey-glide-sweep \
    --dry-run

# Run the matrix benchmark (results land in results/valkey-glide-sweep/<run-id>/)
python scripts/run_benchmark_matrix.py \
    --matrix configs/matrices/valkey-glide-thread-sweep.json \
    --output-dir results/valkey-glide-sweep \
    --run-id first-try \
    --server-host 10.0.0.5

# Generate interactive graphs from one run's results
python scripts/generate_interactive_graphs.py \
    results/valkey-glide-sweep/first-try/ \
    --output graphs/interactive/valkey-glide-sweep/
```

Or via Makefile:
```bash
make benchmark-matrix-dry-run MATRIX=configs/matrices/valkey-glide-thread-sweep.json
make benchmark-matrix MATRIX=configs/matrices/valkey-glide-thread-sweep.json \
    OUTPUT_DIR=results/glide-sweep SERVER_HOST=10.0.0.5

# Graphs for the run that just finished (follows OUTPUT_DIR/latest)
make benchmark-matrix-graphs OUTPUT_DIR=results/glide-sweep
# ...or for a specific run
make benchmark-matrix-graphs OUTPUT_DIR=results/glide-sweep RUN_ID=20260321T140322Z
```

## Engine Builds — Once Per Sweep

Before the sweep starts, the orchestrator resolves the engine behind every `driver_config` (via the driver's `driver_id`) and runs `make <engine>-build` **once for each engine the matrix actually needs**. A Java-only matrix builds Java only; a mixed matrix builds Java, Ruby and C#. If a build fails the run aborts immediately, rather than failing every cell.

Individual cells then execute `make <engine>-run-nobuild`, which runs the already-built engine without rebuilding it. This matters because sweeps are large — `driver-comparison-high-tps` is 720 cells — and `*-build` is not incremental (`mvn clean package`, `bundle install`, `dotnet build -c Release`).

| Target | Behavior |
|--------|----------|
| `make java-run` / `ruby-run` / `csharp-run` | Build, then run. Unchanged — the right target for one-off manual runs. |
| `make java-run-nobuild` / `ruby-run-nobuild` / `csharp-run-nobuild` | Run only. Assumes the engine is already built; used by the matrix orchestrator. |

`make benchmark-matrix` no longer pre-builds Java itself, since the orchestrator builds exactly the engines the chosen matrix requires.

## Matrix Config Format

Matrix configs live in `configs/matrices/` and define **dimensions** to sweep:

```json
{
    "description": "Glide JNI thread configuration sweep",
    "x_axis": "connections",
    "iterations": 10,
    "dimensions": {
        "connections": [1, 2, 4, 8, 16, 32, 64, 128],
        "driver_config": [
            "configs/drivers/high-throughput/spring-data-valkey-glide.json"
        ],
        "pool_size": "$connections",
        "env": [
            {"GLIDE_TOKIO_WORKER_THREADS": "8", "GLIDE_CALLBACK_WORKER_THREADS": "8"},
            {"GLIDE_TOKIO_WORKER_THREADS": "16", "GLIDE_CALLBACK_WORKER_THREADS": "16"},
            {"GLIDE_TOKIO_WORKER_THREADS": "24", "GLIDE_CALLBACK_WORKER_THREADS": "24"}
        ]
    }
}
```

### Fields

| Field | Required | Description |
|-------|----------|-------------|
| `description` | No | Human-readable description of the experiment |
| `x_axis` | Yes | Which dimension goes on the chart X axis (typically `"connections"`) |
| `iterations` | No | Number of repetitions per combo (default: 10) |
| `server_host` | No | Server hostname (overridable via CLI `--server-host`) |
| `port` | No | Server port (default: 6379) |
| `workload_template` | **Yes** | Workload JSON template (default: `configs/workloads/reference/basic-standalone-single-client-1M-reqs.json`) |
| `cpu_interval` | No | CPU monitor sampling interval in seconds (default: 0.5) |
| `dimensions` | Yes | Dict of dimension name → values (see below) |

### Dimension Types

| Type | Syntax | Behavior |
|------|--------|----------|
| **Array** | `[1, 2, 4]` | Free dimension — participates in Cartesian product |
| **Binding** | `"$connections"` | Mirrors another dimension's value at each data point |
| **Scalar** | `32` or `true` | Fixed value, applied to all runs but not varied |
| **Conditional** | `{"values": [...], "applies_to": {...}}` | Only applies to matching drivers |

### Well-Known Dimensions

| Dimension | Purpose |
|-----------|---------|
| `connections` | Number of client connections (maps to workload `phases[].connections`) |
| `driver_config` | **Required.** Path(s) to driver config JSON files |
| `pool_size` | Sets `specific_driver_config.pool_size` in the driver config |
| `use_pooling` | Sets `specific_driver_config.use_pooling` |
| `share_native_connection` | Sets `specific_driver_config.share_native_connection` |
| `env` | Environment variables passed to the benchmark JVM process |

## Bindings — Dynamic Parameter Linking

Use `"$dimension_name"` to bind a parameter to another dimension:

```json
"pool_size": "$connections"
```

This means pool_size always equals the current connections value at each data point. Bindings can be mixed with concrete values in arrays:

```json
"pool_size": [8, 32, "$connections"]
```

This produces 3 series: one with fixed pool=8, one with pool=32, and one where pool tracks connections.

## Conditional Dimensions — `applies_to`

Some dimensions only make sense for certain drivers. Use `applies_to` with glob patterns on the `driver_config` path:

```json
"pool_size": {
    "values": ["$connections"],
    "applies_to": {"driver_config": ["*spring-data*"]}
},
"env": {
    "values": [
        {"GLIDE_TOKIO_WORKER_THREADS": "16", "GLIDE_CALLBACK_WORKER_THREADS": "16"}
    ],
    "applies_to": {"driver_config": ["*glide*"]}
}
```

Non-matching drivers skip the dimension entirely, avoiding wasted benchmark time on meaningless parameter combinations.

## Example Configs

| Config | Use Case |
|--------|----------|
| `driver-comparison-defaults.json` | All drivers with default (out-of-box) configs |
| `driver-comparison-high-tps.json` | All drivers tuned for maximum throughput |
| `valkey-glide-thread-sweep.json` | Sweep Valkey-Glide JNI thread configs (tokio workers × callback workers) |
| `lettuce-pool-sweep.json` | Compare Lettuce with fixed pool=8, pool=32, and pool=connections |


## Output Format

The matrix runner produces a **flat directory per run**, under `<output-dir>/<run-id>/`:

```
results/glide-sweep/
    20260321T140322Z/
        spring-data-valkey-glide@cb=8,tw=8,pool_size=connections.ndjson
        spring-data-valkey-glide@cb=16,tw=16,pool_size=connections.ndjson
        *.cpu.ndjson                # CPU samples per variant
        _manifest.json              # Maps labels → config metadata + per-cell outcomes
    latest -> 20260321T140322Z      # symlink to the most recent successful start
```

The run id defaults to a UTC timestamp, so two runs into the same `--output-dir`
never merge into the same NDJSON files. Pass `--run-id` to name a run yourself;
if that run directory already holds results, the run is refused unless you pass
`--resume` (append deliberately) or `--overwrite` (discard them first). Point the
graph generator at the run directory, not at `--output-dir`.

Once preflight passes, the orchestrator repoints `<output-dir>/latest` at the
current run, so tooling can find the newest results without knowing the run id
(`--resume` repoints it at the run being appended to). A failed preflight leaves
the link on the previous run, and a `latest` that is a real directory rather than
a symlink is never touched.

Each `.ndjson` file contains STEADY phase records for ALL connection counts (multiple iterations each). The NDJSON format is identical to what the benchmark engine produces — no changes to the output schema.

The `_manifest.json` records the full configuration for each variant, enabling the graph generator to build rich legend labels. It also records what actually ran:

```json
{
  "run_id": "20260321T140322Z",
  "variants": { "...": {} },
  "summary": {"planned": 6, "attempted": 6, "succeeded": 5, "failed": 1},
  "cells": [
    {
      "iteration": 1, "x_axis": "connections", "x_value": 4,
      "label": "jedis", "driver_config": "configs/drivers/default/jedis.json",
      "engine": "java", "metrics_output": "jedis.ndjson",
      "started_at": "2026-03-21T14:03:22Z", "status": "ok",
      "records_written": 1, "duration_seconds": 41.2
    }
  ]
}
```

A cell counts as failed if the engine exits non-zero, if the pre-cell FLUSHALL
fails, or if the engine exits 0 but writes no new metrics record.

## Server Preconditions

Before the first benchmark runs, the orchestrator resolves a CLI binary and
PINGs the server with bounded retry, so an unreachable endpoint fails up front
instead of aborting mid-sweep. The CLI is resolved in this order:

1. `$RESP_BENCH_CLI`
2. `work/<project>/bin/<project>-cli` — the binary the Makefile builds, where
   `<project>` is `$SERVER_PROJECT` (default `valkey`)
3. `<project>-cli`, then `valkey-cli`, then `redis-cli` on `PATH`

Auth and TLS settings from the driver config (`auth.username`, `auth.password`,
`tls.*`) are passed to the probe and to the per-cell FLUSHALL. Matrices built
only from serverless drivers (`driver_id: "recording"`) skip both the probe and
the flush entirely.

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Every attempted cell succeeded |
| 1 | At least one cell failed (the sweep still ran to the end) |
| 2 | Preflight failed and nothing ran: server unreachable, no CLI binary, populated run directory, or a matrix with no cells |

## CLI Reference

```
python scripts/run_benchmark_matrix.py --help

  --matrix, -m        Path to matrix configuration JSON file (required)
  --output-dir, -o    Base directory for results; results land in <output-dir>/<run-id>/ (required)
  --run-id            Name of this run's subdirectory (default: UTC timestamp)
  --resume            Allow appending into a run directory that already has results
  --overwrite         Delete existing results in the run directory first
  --server-host       Server hostname (overrides matrix config)
  --port              Server port (overrides matrix config)
  --iterations        Override iterations from matrix config
  --dry-run           Show plan without running benchmarks
```

## Note on Glide JNI Thread Configuration

`GLIDE_TOKIO_WORKER_THREADS` and `GLIDE_CALLBACK_WORKER_THREADS` are **process-level environment variables** consumed by the native Rust/Tokio runtime inside the valkey-glide JAR. They are read once when `GlideClient.createClient()` first initializes the process-wide Tokio runtime, and cannot be changed afterward.

Because the matrix runner launches each benchmark as a separate JVM process (via `make java-run-nobuild`), different env var values can be set per run. These are specified in the matrix config's `env` dimension, NOT in the driver config JSON.
