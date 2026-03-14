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

# Run the matrix benchmark
python scripts/run_benchmark_matrix.py \
    --matrix configs/matrices/valkey-glide-thread-sweep.json \
    --output-dir results/valkey-glide-sweep \
    --server-host 10.0.0.5

# Generate interactive graphs from results
python scripts/generate_interactive_graphs.py \
    results/valkey-glide-sweep/ \
    --output graphs/interactive/valkey-glide-sweep/
```

Or via Makefile:
```bash
make benchmark-matrix-dry-run MATRIX=configs/matrices/valkey-glide-thread-sweep.json
make benchmark-matrix MATRIX=configs/matrices/valkey-glide-thread-sweep.json \
    OUTPUT_DIR=results/glide-sweep SERVER_HOST=10.0.0.5
```

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

The matrix runner produces a **flat directory**:

```
results/glide-sweep/
    spring-data-valkey-glide@cb=8,tw=8,pool_size=connections.ndjson
    spring-data-valkey-glide@cb=16,tw=16,pool_size=connections.ndjson
    *.cpu.ndjson                    # CPU samples per variant
    _manifest.json                  # Maps labels → config metadata
```

Each `.ndjson` file contains STEADY phase records for ALL connection counts (multiple iterations each). The NDJSON format is identical to what the benchmark engine produces — no changes to the output schema.

The `_manifest.json` records the full configuration for each variant, enabling the graph generator to build rich legend labels.

## CLI Reference

```
python scripts/run_benchmark_matrix.py --help

  --matrix, -m        Path to matrix configuration JSON file (required)
  --output-dir, -o    Directory to write benchmark results (required)
  --server-host       Server hostname (overrides matrix config)
  --port              Server port (overrides matrix config)
  --iterations        Override iterations from matrix config
  --dry-run           Show plan without running benchmarks
```

## Note on Glide JNI Thread Configuration

`GLIDE_TOKIO_WORKER_THREADS` and `GLIDE_CALLBACK_WORKER_THREADS` are **process-level environment variables** consumed by the native Rust/Tokio runtime inside the valkey-glide JAR. They are read once when `GlideClient.createClient()` first initializes the process-wide Tokio runtime, and cannot be changed afterward.

Because the matrix runner launches each benchmark as a separate JVM process (via `make java-run`), different env var values can be set per run. These are specified in the matrix config's `env` dimension, NOT in the driver config JSON.
