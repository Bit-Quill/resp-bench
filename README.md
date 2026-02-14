# resp-bench

A multi-language benchmark suite for RESP protocol (Redis/Valkey) compatible databases and client libraries.

## Overview

**resp-bench** provides unified benchmark functionality across multiple programming languages, enabling fair comparisons between different client libraries and implementations. All language engines share the same:

- **Configuration format** - JSON-based driver and workload configurations
- **Traffic generation algorithms** - Consistent key generation, rate limiting, and workload patterns
- **Metrics output** - NDJSON with HdrHistogram for cross-language analysis

## Supported Languages

| Language | Status | Supported Drivers |
|----------|--------|-------------------|
| Java | ✅ Ready | Jedis, Lettuce, Valkey-Glide, Redisson, Spring Data Valkey/Redis |
| Python | 🚧 Planned | redis-py, aioredis, valkey-glide |
| Go | 📋 Future | go-redis, rueidis |
| Node.js | 📋 Future | ioredis, node-redis |

## Quick Start

### Prerequisites

- Make (for server management)
- Language-specific build tools (Maven for Java, pip for Python, etc.)

### 1. Start a Server

```bash
# Start standalone Valkey/Redis server on port 6379
make server-standalone-start

# Or start a cluster (ports 7379-7382)
make server-cluster-init
```

### 2. Run a Benchmark

**Java:**
```bash
make java-run \
  DRIVER=configs/drivers/example-jedis-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json
```

**Python (when available):**
```bash
make python-run \
  DRIVER=configs/drivers/example-redis-py-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json
```

### 3. Stop Servers

```bash
make server-stop
```

## Project Structure

```
resp-bench/
├── README.md                    # This file
├── LICENSE                      # Apache 2.0
├── Makefile                     # Server management + language targets
├── configs/                     # Shared configuration files
│   ├── schemas/                 # JSON schemas for validation
│   ├── drivers/                 # Driver configurations
│   └── workloads/               # Workload definitions
├── config-editor/               # React-based configuration editor UI
├── java/                        # Java benchmark engine
├── python/                      # Python benchmark engine (planned)
├── docs/                        # Documentation
│   ├── ARCHITECTURE.md          # System architecture
│   ├── ADDING_LANGUAGE.md       # Guide for adding new languages
│   └── CONFIG_SPECIFICATION.md  # Configuration format spec
└── output/                      # Default metrics output directory
```

## Configuration

### Driver Configuration

Defines which client library to use:

```json
{
  "schema_version": "1.0",
  "description": "Jedis client - standalone mode",
  "driver_id": "jedis",
  "mode": "standalone",
  "specific_driver_config": {}
}
```

### Workload Configuration

Defines the benchmark phases and traffic patterns:

```json
{
  "schema_version": "1.0",
  "benchmark_profile": {
    "name": "Example Benchmark",
    "description": "Two-phase benchmark"
  },
  "phases": [
    {
      "id": "WARMUP",
      "connections": 10,
      "completion": {"type": "requests", "requests": 1000},
      "keyspace": {
        "keys_count": 1000,
        "key_prefix": "bench:",
        "generation_alg": "sequential_int"
      },
      "commands": [
        {"command": "set", "weight": 1.0, "data_size_bytes": 256}
      ]
    }
  ]
}
```

See [docs/CONFIG_SPECIFICATION.md](docs/CONFIG_SPECIFICATION.md) for full details.

## Metrics Output

All engines output metrics in NDJSON format (one JSON object per line):

```json
{
  "phase": {
    "id": "STEADY",
    "status": "COMPLETED",
    "duration_ms": 60000
  },
  "totals": {
    "requests": 1000000,
    "errors": 0
  },
  "metrics": {
    "GET": {
      "requests": 800000,
      "latency": {
        "unit": "us",
        "summary": {"p50": 120, "p99": 310, "max": 9000},
        "hdr": {"payload_b64": "H4sIAAAAA..."}
      }
    }
  }
}
```

## Make Targets

### Server Management

| Target | Description |
|--------|-------------|
| `make server-start` | Start all servers (standalone + cluster + sentinel) |
| `make server-stop` | Stop all servers |
| `make server-standalone-start` | Start standalone server (port 6379) |
| `make server-cluster-init` | Initialize cluster (ports 7379-7382) |

### Java Engine

| Target | Description |
|--------|-------------|
| `make java-build` | Build Java benchmark JAR |
| `make java-test` | Run unit tests |
| `make java-run` | Run benchmark (uses DRIVER, WORKLOAD, SERVER vars) |
| `make java-info` | Show supported drivers and commands |

### Config Editor

| Target | Description |
|--------|-------------|
| `make config-editor-dev` | Run config editor in development mode |
| `make config-editor-build` | Build config editor for production |

## Comparing Client Libraries

Example: Compare Jedis vs Lettuce on the same workload:

```bash
# Start server
make server-standalone-start

# Run with Jedis
make java-run \
  DRIVER=configs/drivers/example-jedis-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json \
  METRICS_OUTPUT=output/jedis.ndjson

# Run with Lettuce
make java-run \
  DRIVER=configs/drivers/example-lettuce-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json \
  METRICS_OUTPUT=output/lettuce.ndjson

# Stop server
make server-stop

# Compare results
cat output/jedis.ndjson output/lettuce.ndjson | jq '.phase.id, .metrics.GET.latency.summary'
```

## Contributing

We welcome contributions! See:
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - Understand the system design
- [docs/ADDING_LANGUAGE.md](docs/ADDING_LANGUAGE.md) - Add support for a new language

## License

Apache License 2.0 - see [LICENSE](LICENSE)
