# C# Benchmark Engine

## Overview

The C# engine is a .NET implementation of the resp-bench benchmark suite, designed for high-throughput benchmarking of .NET Redis/Valkey client libraries.

## Supported Drivers

| Driver ID | Package | Status | Description |
|-----------|---------|--------|-------------|
| `valkey-glide-csharp` | [Valkey.Glide](https://github.com/valkey-io/valkey-glide-csharp) | ✅ Ready | Valkey GLIDE C# — high-performance Rust-core client with StackExchange.Redis-compatible API |
| `stackexchange-redis` | [StackExchange.Redis](https://github.com/StackExchange/StackExchange.Redis) | ✅ Ready | Most popular .NET Redis client. Multiplexed connection model. |

## Architecture

The C# engine uses a **Task-per-client** architecture equivalent to Java's Virtual Thread-per-client:

```
Main thread
  │
  ├── Creates N client connections (with CPS rate limiting)
  ├── Spawns N long-lived Tasks (one per client)
  │     │
  │     ├── Task-0: while(running) { execute → await → record → repeat }
  │     ├── Task-1: while(running) { execute → await → record → repeat }
  │     ├── ...
  │     └── Task-N: while(running) { execute → await → record → repeat }
  │
  ├── Logs progress periodically
  └── Waits for all Tasks to complete, merges metrics
```

### Key Design Decisions

| Java Concept | C# Equivalent |
|---|---|
| Virtual Threads | `Task.Run()` per client (long-lived) |
| `CompletableFuture.anyOf()` | `Task.WhenAny()` |
| `AtomicLong` | `Interlocked.Increment/Read` |
| `System.nanoTime()` | `Stopwatch.GetTimestamp()` |
| `SynchronizedHistogram` | `LongConcurrentHistogram` (HdrHistogram.NET) |
| `ObjectMapper` (Jackson) | `System.Text.Json.JsonSerializer` |

## Prerequisites

- .NET 10.0 SDK or later
- Valkey/Redis server on localhost:6379 (for integration tests)

## Build & Test

```bash
# Build
make csharp-build

# Run all tests (requires live Valkey server for integration tests)
make csharp-test

# Show driver info
make csharp-info
```

## Usage

```bash
# Using Valkey GLIDE (recommended)
make csharp-run \
  DRIVER=configs/drivers/example-valkey-glide-csharp-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json \
  SERVER=localhost:6379

# Using StackExchange.Redis
make csharp-run \
  DRIVER=configs/drivers/example-stackexchange-redis-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json \
  SERVER=localhost:6379
```

## Test Coverage

The C# engine includes tests ported from the Java reference implementation.
Integration tests are parameterized across **all supported drivers** (`valkey-glide-csharp`, `stackexchange-redis`),
matching Java's `@ParameterizedTest @MethodSource("allDrivers")` pattern.

### Unit Tests (20)
- **ConfigLoaderTest** (7) — JSON parsing, validation, cluster/TLS detection
- **KeyGeneratorTest** (4) — Sequential, random, reset, fork for threads
- **RateLimiterTest** (9) — Create, tryAcquire, constant rate enforcement

### Integration Tests — All Drivers (16 = 8 theories × 2 drivers)
- **MetricsOutputTest** — NDJSON format, request counts, schema validation, multi-phase, histogram encoding
  - Runs each test with both `stackexchange-redis` and `valkey-glide-csharp`

### Integration Tests — Recording Client (33)
- **RecordingClientWorkloadTest** (8) — Key prefix, sequential keys, data sizes, wrap-around
- **ErrorMetricsIntegrationTest** (10) — Error simulation (10%/100%/0%), per-command error rates
- **RateLimitingTest** (7) — RPS limit, CPS limit, combined limits, no-limit throughput
- **MetricsOutputTest** (5 recording-only) — Parallel issuers, long-tail latency, duration-based completion
- **BenchmarkIntegrationTest** (3) — Live server connection tests for each driver

## Configuration

Same JSON configuration files as all other engines. See [CONFIG_SPECIFICATION.md](CONFIG_SPECIFICATION.md).

### Valkey GLIDE C# Config

```json
{
  "driver_id": "valkey-glide-csharp",
  "mode": "standalone"
}
```

### StackExchange.Redis Config

```json
{
  "driver_id": "stackexchange-redis",
  "mode": "standalone"
}
```

## Metrics Output

Produces identical NDJSON format as Java and Ruby engines, including:
- Phase metadata (id, status, timestamps, duration, connections)
- Per-command latency histograms with base64-encoded HdrHistogram payload (V2 format)
- Summary percentiles (p50, p95, p99, p99.9)

## Author

Authored by Ilia Kolominsky
