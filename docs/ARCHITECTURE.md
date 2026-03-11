# Architecture

This document describes the architecture of resp-bench and the design decisions that enable multi-language support with consistent behavior.

## Design Goals

1. **Unified Configuration** - Same JSON configs work across all language engines
2. **Consistent Behavior** - Same workload produces comparable results regardless of language
3. **Fair Comparisons** - Minimize benchmark overhead so measurements reflect client performance
4. **Extensibility** - Easy to add new languages, drivers, and commands

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Configuration                            │
│      ┌──────────────────┐             ┌──────────────────┐      │
│      │  Driver Config   │             │ Workload Config  │      │
│      │  (driver.json)   │             │ (workload.json)  │      │
│      └────────┬─────────┘             └────────┬─────────┘      │
│               │                                │                │
│               └──────────────┬─────────────────┘                │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │              Language-specific Benchmark Engine             ││
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐││
│  │  │Key Generator│ │Rate Limiter │ │  Metrics Collector      │││
│  │  └─────────────┘ └─────────────┘ └─────────────────────────┘││
│  │         │               │                    │              ││
│  │         └───────────────┼────────────────────┘              ││
│  │                         ▼                                   ││
│  │  ┌─────────────────────────────────────────────────────────┐││
│  │  │         Language-specific Client Driver Abstraction     │││
│  │  │  ┌──────┐ ┌────────┐ ┌───────┐ ┌────────┐ ┌──────────┐  │││
│  │  │  │Jedis │ │Lettuce │ │ GLIDE │ │redis-py│ │  go-redis│  │││
│  │  │  └──────┘ └────────┘ └───────┘ └────────┘ └──────────┘  │││
│  │  └─────────────────────────────────────────────────────────┘││
│  └─────────────────────────────────────────────────────────────┘│
│                           │                                     │
│                           ▼                                     │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                 Metrics Output (NDJSON)                     ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

## Core Components

### 1. Configuration Layer

All language engines must parse the same JSON configuration formats:

- **Driver Config** - Specifies which client library to use
- **Workload Config** - Defines benchmark phases and traffic patterns

JSON schemas are provided in `configs/schemas/` for validation.

### 2. Benchmark Engine

Each language engine implements the same benchmark logic:

```
for each phase in workload.phases:
    1. Create N client connections
    2. Apply warmup (PING requests)
    3. Start rate limiters (CPS, RPS)
    4. Execute commands according to weights
    5. Collect latency samples
    6. Output phase metrics
```

### 3. Key Generator

Generates keys deterministically based on configuration:

| Algorithm        | Behavior                             |
|------------------|--------------------------------------|
| `sequential_int` | Keys 0, 1, 2, ... N-1 (wraps around) |
| `uniform_rand`   | Random keys using seeded PRNG        |

**Critical**: All implementations must use the same PRNG algorithm to ensure reproducibility. We standardize on **xoshiro256** or equivalent.

### 4. Rate Limiter

Controls request rate to achieve target throughput:

- **CPS (Connections Per Second)** - Limits connection creation rate
- **RPS (Requests Per Second)** - Limits overall request rate

Implementation uses token bucket algorithm.

### 5. Metrics Collector

Uses HdrHistogram for latency collection:
- Microsecond precision
- 3 significant figures
- Range: 1µs to 1 hour

All implementations serialize histograms in the same format for cross-language analysis.

### 6. Client Driver Abstraction

Each language defines a common interface that drivers implement:

**Java:**
```java
public interface BenchmarkClient {
    void connect(List<String> servers, DriverConfig config);
    CompletableFuture<TimedResult<String>> get(String key);
    CompletableFuture<TimedResult<Void>> set(String key, byte[] value);
    CompletableFuture<TimedResult<String>> ping();
    void close();
}
```

**Python:**
```python
class BenchmarkClient(ABC):
    async def connect(self, servers: List[str], config: DriverConfig): ...
    async def get(self, key: str) -> TimedResult[str]: ...
    async def set(self, key: str, value: bytes) -> TimedResult[None]: ...
    async def ping(self) -> TimedResult[str]: ...
    async def close(self): ...
```

## Metrics Output Format

All engines produce identical NDJSON output:

```json
{
  "phase": {
    "id": "string",
    "status": "COMPLETED|ERROR",
    "start_timestamp": "ISO-8601",
    "finish_timestamp": "ISO-8601",
    "duration_ms": 0,
    "connections": 0
  },
  "totals": {
    "requests": 0,
    "errors": 0
  },
  "metrics": {
    "<COMMAND>": {
      "requests": 0,
      "errors": 0,
      "latency": {
        "unit": "us",
        "count": 0,
        "summary": {
          "min": 0,
          "p50": 0,
          "p95": 0,
          "p99": 0,
          "p999": 0,
          "max": 0
        },
        "hdr": {
          "format": "hdr",
          "sigfig": 3,
          "payload_b64": "base64-encoded-histogram"
        }
      }
    }
  }
}
```

## Language Engine Structure

Each language engine follows this structure:

```
<language>/
├── README.md              # Language-specific documentation
├── <build-file>           # pom.xml, pyproject.toml, go.mod, etc.
└── src/
    ├── main entry point
    ├── client/
    │   ├── interface definition
    │   └── impl/
    │       └── driver implementations
    ├── command/
    │   └── command implementations
    ├── config/
    │   └── config parsers
    ├── engine/
    │   ├── benchmark engine
    │   ├── key generator
    │   └── rate limiter
    └── metrics/
        └── metrics collector
```

## Concurrency Model

Different languages use appropriate concurrency primitives:

| Language | Model                                           |
|----------|-------------------------------------------------|
| Java     | Virtual Threads (Java 21+) or CompletableFuture |
| Python   | asyncio with async/await                        |
| Go       | goroutines and channels                         |
| Node.js  | Promise/async-await                             |

The key requirement is that N connections can operate concurrently, each potentially with pipeline_depth in-flight requests.

## Parallel Command Issuers (Java)

At high connection counts (128+), a single command-issuing thread becomes a CPU bottleneck — saturating one core on semaphore contention, key generation (`String.format()`), and round-robin scanning. To address this, the Java engine supports **parallel command issuer threads** that partition client connections across multiple threads.

### Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                    Shared (thread-safe)                        │
│  AtomicLong requestCount, pendingCount                        │
│  MetricsCollector (SynchronizedHistogram + ConcurrentHashMap) │
│  RateLimiter (Semaphore-based)                                │
└────────────────────────────────────────────────────────────────┘
        │                    │                    │
   ┌────▼─────┐       ┌─────▼────┐        ┌─────▼────┐
   │ issuer-0 │       │ issuer-1 │        │ issuer-N │
   │──────────│       │──────────│        │──────────│
   │ Slots    │       │ Slots    │        │ Slots    │
   │  0..63   │       │ 64..127  │        │ 192..255 │
   │ Semaphore│       │ Semaphore│        │ Semaphore│
   │ KeyGen   │       │ KeyGen   │        │ KeyGen   │
   │ CmdSel   │       │ CmdSel   │        │ CmdSel   │
   └──────────┘       └──────────┘        └──────────┘
```

### Thread-local state (no contention)
- **Partition of ClientSlots** — each thread manages only its subset
- **Semaphore** — permits = sum of pipeline depths in its partition
- **KeyGenerator** — forked with unique seed per thread (shared AtomicLong counter for sequential mode)
- **CommandSelector** — independent `Random` instance

### Auto-detection
By default, the number of issuer threads is auto-computed:
```
threads = max(1, min(connections / 32, availableProcessors))
```
This can be overridden with the `--command-issuer-threads` CLI flag.

| Connections | Default Threads |
|-------------|----------------|
| 1–31        | 1              |
| 32–63       | 1              |
| 64–95       | 2              |
| 128–159     | 4              |
| 256+        | 8              |

## Warmup Strategy

All engines implement the same warmup strategy:
1. Submit warmup PING requests using the same semaphore slots
2. Start measured workload immediately (don't wait for warmup)
3. Warmup requests occupy slots, forcing measured requests to wait
4. As warmup completes, slots free up gradually
5. Result: No burst, smooth ramp-up

## Testing Requirements

Each language engine must pass:

1. **Unit tests** for key generator, rate limiter, config parsing
2. **Integration tests** against a live server
3. **Cross-language validation** - Same config must produce statistically similar results

## Performance Considerations

To minimize benchmark overhead:
- Pre-allocate data buffers
- Reuse key strings when possible
- Avoid allocations in hot paths
- Use efficient histogram implementations
