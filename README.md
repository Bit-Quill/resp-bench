# resp-bench

A multi-language benchmark suite for RESP protocol (Redis/Valkey) compatible databases and client libraries, with a matrix-based orchestration layer for multi-dimensional parameter sweeps and interactive graph generation.

> 📐 See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full architecture diagram and component details.

## Quick Start

### Prerequisites

- Python 3.8+, Java 21+, Maven
- Make
- A server CLI (`valkey-cli`) for the matrix runner's readiness probe and per-cell
  FLUSHALL — the Makefile's `server-*` targets build one into
  `work/<SERVER_PROJECT>/bin/`, or any `valkey-cli`/`redis-cli` on `PATH` is used.
  Set `RESP_BENCH_CLI` to point at a specific binary. Not needed for matrices that
  only use the serverless `recording` driver.

### 1. Run a Benchmark Matrix

```bash
# See what would run (dry run)
python scripts/run_benchmark_matrix.py \
    --matrix configs/matrices/driver-comparison-high-tps.json \
    --output-dir results/my-run \
    --dry-run

# Run for real (needs a Valkey/Redis server)
make server-standalone-start
python scripts/run_benchmark_matrix.py \
    --matrix configs/matrices/driver-comparison-high-tps.json \
    --output-dir results/my-run \
    --run-id first-try \
    --server-host localhost
# Results land in results/my-run/<run-id>/ (--run-id defaults to a UTC timestamp).
# Exit code: 0 = all cells ran, 1 = some cell failed, 2 = preflight failed.
```

### 2. Generate Interactive Graphs

```bash
python scripts/generate_interactive_graphs.py \
    results/my-run/first-try/ \
    --output graphs/interactive/my-run/ \
    --title "My Benchmark Run"
# Or, for whichever run finished most recently:
#   make benchmark-matrix-graphs OUTPUT_DIR=results/my-run
# Open graphs/interactive/my-run/scalability_and_delta.html in a browser
```

### 3. Run a Single Engine Directly

```bash
make java-run \
  DRIVER=configs/drivers/default/jedis.json \
  WORKLOAD=configs/workloads/example-workload.json \
  SERVER=localhost:6379
```

## Matrix Orchestrator

The matrix orchestrator (`run_benchmark_matrix.py`) sweeps a **Cartesian product of configurable dimensions** — different drivers, thread configurations, pool sizes, environment variables — producing results for interactive visualization.

```json
{
    "x_axis": "connections",
    "workload_template": "configs/workloads/reference/basic-standalone-single-client-1M-reqs.json",
    "dimensions": {
        "connections": [1, 4, 16, 64, 128],
        "driver_config": ["configs/drivers/high-throughput/spring-data-valkey-glide.json"],
        "pool_size": "$connections",
        "env": [
            {"GLIDE_TOKIO_WORKER_THREADS": "1", "GLIDE_CALLBACK_WORKER_THREADS": "2"},
            {"GLIDE_TOKIO_WORKER_THREADS": "2", "GLIDE_CALLBACK_WORKER_THREADS": "4"},
            {"GLIDE_TOKIO_WORKER_THREADS": "8", "GLIDE_CALLBACK_WORKER_THREADS": "16"}
        ]
    }
}
```

Features: dimension bindings (`$connections`), conditional dimensions (`applies_to`), environment variable injection, `_manifest.json` metadata output.

📖 [Full matrix orchestrator documentation](docs/BENCHMARK_MATRIX.md)

## Interactive Graph Generator

Produces self-contained HTML files with Plotly.js charts: RPS scalability, latency percentiles (p50/p95/p99/p999), CPU usage, efficiency, and delta comparison charts. Supports both legacy (subdirectory-per-client-count) and flat (matrix output) layouts.

📖 [Full graph generator documentation](docs/INTERACTIVE_GRAPHS.md)

## System Monitor

Thread-based system metrics collector that runs alongside benchmarks, collecting CPU% (system-wide via `/proc/stat`), memory RSS (per process group via `/proc/<pid>/status`), and system memory availability. Outputs `.system.ndjson` with each sample.

## Supported Languages

| Language | Status | Drivers |
|----------|--------|---------|
| Java | ✅ Ready | Jedis, Lettuce, Valkey-Glide, Redisson, Spring Data Valkey/Redis |
| Ruby | ✅ Ready | redis-rb, valkey-glide-ruby |
| C# | ✅ Ready | valkey-glide-csharp, StackExchange.Redis |
| Python | 🚧 Planned | redis-py, aioredis, valkey-glide |

## Project Structure

```
resp-bench/
├── Makefile                     # Server management + all targets
├── configs/
│   ├── drivers/                 # Driver configurations (default/, high-throughput/)
│   ├── workloads/               # Workload definitions (reference/)
│   ├── matrices/                # Matrix orchestrator configs
│   ├── schemas/                 # JSON schemas for validation
│   └── test/                    # E2E test configs
│       ├── drivers/             # Recording client configs
│       ├── matrices/            # Test matrix configs
│       └── workloads/           # Short test workloads
├── scripts/
│   ├── run_benchmark_matrix.py  # Matrix orchestrator
│   ├── generate_interactive_graphs.py  # Interactive graph generator
│   ├── system_monitor.py        # Thread-based CPU/memory monitor
│   └── tests/                   # Python test suite (108 tests)
│       ├── test_outlier_detection.py
│       ├── test_matrix_config.py
│       ├── test_graph_data_loading.py
│       ├── test_graph_html_output.py
│       ├── test_system_monitor.py
│       └── test_e2e_pipeline.py  # E2E: engine → NDJSON → graphs
├── java/                        # Java benchmark engine
├── ruby/                        # Ruby benchmark engine
├── csharp/                      # C# (.NET 10) benchmark engine
├── docs/
│   ├── ARCHITECTURE.md          # System architecture
│   ├── BENCHMARK_MATRIX.md      # Matrix orchestrator docs
│   ├── INTERACTIVE_GRAPHS.md    # Graph generator docs
│   ├── CONFIG_SPECIFICATION.md  # Configuration format spec
│   ├── BENCHMARKS_JAVA.md       # Java benchmark details
│   ├── BENCHMARKS_CSHARP.md     # C# benchmark details
│   └── BENCHMARKS_RUBY.md       # Ruby benchmark details
└── graphs/interactive/          # Generated HTML graphs
```

## Configuration

### Driver Configuration

```json
{
  "driver_id": "spring-data-valkey",
  "mode": "standalone",
  "specific_driver_config": {
    "secondary_driver_id": "valkey-glide",
    "pool_size": 32
  }
}
```

### Workload Configuration

```json
{
  "phases": [{
    "id": "STEADY",
    "connections": 64,
    "commands": [
      {"command": "set", "weight": 0.5, "data_size_bytes": 512},
      {"command": "get", "weight": 0.5}
    ],
    "completion": {"type": "requests", "requests": 1000000}
  }]
}
```

See [docs/CONFIG_SPECIFICATION.md](docs/CONFIG_SPECIFICATION.md) for full details.

## Make Targets

### Benchmark Matrix

| Target | Description |
|--------|-------------|
| `make benchmark-matrix` | Run matrix benchmark (MATRIX, OUTPUT_DIR, SERVER_HOST) |
| `make benchmark-matrix-dry-run` | Show plan without running |
| `make benchmark-matrix-graphs` | Generate graphs from results |

### Testing

| Target | Description |
|--------|-------------|
| `make test-scripts` | Run 99 Python unit tests (~12s) |
| `make test-scripts-e2e` | Run 9 e2e integration tests (~7min, builds Java) |
| `make test-scripts-all` | Run all 108 tests |
| `make java-test` | Run Java unit tests |
| `make ruby-test` | Run Ruby tests |
| `make csharp-test` | Run C# tests |

### Engines

| Target | Description |
|--------|-------------|
| `make java-run` | Run Java engine (DRIVER, WORKLOAD, SERVER) |
| `make ruby-run` | Run Ruby engine (DRIVER, WORKLOAD, SERVER) |
| `make csharp-run` | Run C# engine (DRIVER, WORKLOAD, SERVER) |
| `make java-build` | Build Java JAR |
| `make csharp-build` | Build C# executable |

### Server Management

| Target | Description |
|--------|-------------|
| `make server-standalone-start` | Start standalone server (port 6379) |
| `make server-cluster-init` | Initialize cluster (ports 7379-7382) |
| `make server-stop` | Stop all servers |

## Contributing

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — System architecture
- [docs/ADDING_LANGUAGE.md](docs/ADDING_LANGUAGE.md) — Add support for a new language

## Author

Authored by Ilia Kolominsky

## License

Apache License 2.0 — see [LICENSE](LICENSE)
