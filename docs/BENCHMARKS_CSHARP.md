# C# Benchmark Engine

## Overview

The C# engine is a .NET implementation of the resp-bench benchmark suite, designed for high-throughput benchmarking of .NET Redis/Valkey client libraries.

## Supported Drivers

| Driver ID | Package | Status | Description |
|-----------|---------|--------|-------------|
| `stackexchange-redis` | [StackExchange.Redis](https://github.com/StackExchange/StackExchange.Redis) | ✅ Ready | Most popular .NET Redis client. Multiplexed connection model. |
| `valkey-glide-csharp` | [valkey-glide-csharp](https://github.com/valkey-io/valkey-glide-csharp) | 🔧 Skeleton | Awaiting NuGet package availability. Client structure ready. |

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

- .NET 8.0 SDK or later (targets the installed runtime version)

## Build & Test

```bash
# Build
make csharp-build

# Run unit tests
make csharp-test

# Run integration tests (requires running Valkey server)
make csharp-integration-test

# Show driver info
make csharp-info
```

## Usage

```bash
make csharp-run \
  DRIVER=configs/drivers/example-stackexchange-redis-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json \
  SERVER=localhost:6379
```

## Test Coverage

The C# engine includes tests ported from the Java reference implementation:

### Unit Tests
- **ConfigLoaderTest** — JSON parsing, validation, cluster/TLS detection
- **KeyGeneratorTest** — Sequential, random, reset, fork for threads
- **RateLimiterTest** — Create, tryAcquire, constant rate enforcement

### Integration Tests (Recording Client)
- **MetricsOutputTest** — NDJSON format, request counts, schema validation, multi-phase
- **RecordingClientWorkloadTest** — Key prefix, sequential keys, data sizes
- **ErrorMetricsIntegrationTest** — Error simulation (10%/100%/0%), error rates
- **RateLimitingTest** — RPS limit, CPS limit, combined limits, no-limit throughput

## Configuration

Same JSON configuration files as all other engines. See [CONFIG_SPECIFICATION.md](CONFIG_SPECIFICATION.md).

### StackExchange.Redis-Specific Config

```json
{
  "driver_id": "stackexchange-redis",
  "mode": "standalone",
  "specific_driver_config": {
    // StackExchange.Redis-specific options can be added here
  }
}
```

## Metrics Output

Produces identical NDJSON format as Java and Ruby engines, including:
- Phase metadata (id, status, timestamps, duration, connections)
- Per-command latency histograms with base64-encoded HdrHistogram payload
- Summary percentiles (p50, p95, p99, p99.9)

## Author

Authored by Ilia Kolominsky
