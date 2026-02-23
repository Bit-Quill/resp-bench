# resp-bench Ruby Engine

Ruby implementation of the resp-bench benchmark suite for Redis/Valkey compatible databases.

## Supported Drivers

| Driver ID | Gem | Description |
|-----------|-----|-------------|
| `redis-rb` | `redis` | Standard Redis client for Ruby |
| `valkey-glide-ruby` | `valkey` | Valkey GLIDE client for Ruby ([GitHub](https://github.com/valkey-io/valkey-glide-ruby)) |

## Installation

```bash
cd ruby
bundle install
```

## Usage

### Command Line

```bash
# Run a benchmark
bundle exec ruby bin/resp-bench \
  --server localhost:6379 \
  --driver ../configs/drivers/example-redis-rb-standalone.json \
  --workload ../configs/workloads/example-workload.json \
  --metrics output/ruby-redis-rb.ndjson

# Show help
bundle exec ruby bin/resp-bench --help

# Show supported drivers and commands
bundle exec ruby bin/resp-bench --info
```

### Using Make (from project root)

```bash
# Build/install dependencies
make ruby-build

# Run tests
make ruby-test

# Run benchmark
make ruby-run \
  DRIVER=configs/drivers/example-redis-rb-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json
```

## Configuration

The Ruby engine uses the same JSON configuration format as other language engines.

### Driver Configuration

```json
{
  "schema_version": "1.0",
  "description": "redis-rb client - standalone mode",
  "driver_id": "redis-rb",
  "mode": "standalone",
  "specific_driver_config": {}
}
```

### Workload Configuration

See [CONFIG_SPECIFICATION.md](../docs/CONFIG_SPECIFICATION.md) for full details.

## Architecture

The Ruby implementation follows the same architecture as the Java reference implementation:

```
lib/resp_bench/
├── client/           # Client interface and implementations
│   ├── benchmark_client.rb       # Abstract interface
│   └── impl/
│       ├── redis_rb_client.rb    # redis-rb implementation
│       └── valkey_glide_client.rb # valkey-glide-ruby implementation
├── command/          # Command implementations
│   └── impl/
│       ├── get_command.rb
│       ├── set_command.rb
│       └── ping_command.rb
├── config/           # Configuration parsing
├── engine/           # Benchmark engine
│   ├── benchmark_engine.rb       # Main orchestrator
│   ├── key_generator.rb          # Java-compatible LCG
│   └── rate_limiter.rb           # Leaky bucket
└── metrics/          # Metrics collection and output
    ├── metrics_collector.rb      # HdrHistogram-based
    └── ndjson_writer.rb          # NDJSON output
```

## Concurrency Model

The Ruby engine uses a **thread-per-client** design for concurrent request execution:

- Each client connection runs in its own dedicated thread
- Maximum of **256 threads** to prevent OS overload with large connection counts
- If connections > 256, threads share clients via round-robin distribution
- Works well with synchronous Redis clients (MRI's GIL is released during I/O)

**Backpressure** is controlled via a `SizedQueue`:
- Queue size equals the total pipeline depth across all connections
- Producer (main thread) blocks when queue is full
- Provides natural rate limiting when server is slow

**Progress logging** reports status every 10 seconds:
```
Progress: 100000/1000000 requests (10.0%) - 45000 req/s
Progress: 200000/1000000 requests (20.0%) - 48000 req/s
```

## Key Generator

The Ruby implementation uses a **Java-compatible LCG (Linear Congruential Generator)** to ensure identical key sequences across languages when using the same seed.

```ruby
# Java's Random uses these constants:
MULTIPLIER = 0x5DEECE66D  # 25214903917
ADDEND = 0xB               # 11
MASK = (1 << 48) - 1
```

This ensures that with `generation_alg: "uniform_rand"` and the same `seed`, Ruby and Java produce identical key sequences.

## Rate Limiter

Uses a **leaky bucket algorithm** that enforces constant rate without burst:

- Operations are evenly spaced at the configured rate
- For `rps_limit: 20`, operations are spaced ~50ms apart
- No burst capacity - rate is strictly enforced from first operation

## Testing

```bash
# Run all tests
bundle exec rake test

# Run only unit tests
bundle exec rake unit

# Run only integration tests (requires running server)
VALKEY_HOST=localhost VALKEY_PORT=6379 bundle exec rake integration
```

### Test Coverage (Java Parity)

The Ruby test suite mirrors the Java reference implementation tests to ensure cross-language consistency:

| Java Test Class | Ruby Test File | Coverage |
|-----------------|---------------|----------|
| `ConfigLoaderTest` | `test/unit/config_loader_test.rb` | Config parsing, cluster/standalone modes, TLS, missing fields |
| `KeyGeneratorTest` | `test/unit/key_generator_test.rb` | Sequential keys, uniform random, Java LCG compatibility |
| `RateLimiterTest` | `test/unit/rate_limiter_test.rb` | Leaky bucket, rate enforcement, multi-connection sharing |
| `JavaRandomTest` | `test/unit/java_random_test.rb` | Cross-language LCG determinism |
| `MetricsOutputTest` | `test/integration/metrics_output_test.rb` | NDJSON format, phase metadata, request counts, HDR histogram, latency p50 validation, long-tail distribution accuracy |
| `ErrorMetricsIntegrationTest` | `test/integration/error_metrics_integration_test.rb` | Error rate simulation, per-command error tracking, connection error detection, duration-based errors |
| `RecordingBenchmarkClientTest` | `test/integration/recording_client_workload_test.rb` | Key generation, data sizes, command recording |
| `RateLimitingIntegrationTest` | `test/integration/rate_limiting_test.rb` | RPS limit enforcement, CPS limit, multi-connection rate sharing |
| `BenchmarkClientTest` | `test/integration/client_test.rb` | Redis-rb client connectivity, CRUD operations |

**Known differences from Java:**
- HDR histogram `payload_b64` may be empty — Ruby's `hdr_histogram` gem does not fully support the `encode` method; summary percentiles are validated instead
- Connection error test uses `connected?` check instead of `assert_raises` — redis-rb uses lazy connections (errors surface on first command, not during `connect`)

## Metrics Output

The Ruby engine produces NDJSON output compatible with all other language engines:

```json
{
  "metadata": {
    "commit_id": "abc123",
    "timestamp": "2026-02-19T09:00:00Z",
    "driver_id": "redis-rb",
    "primary_driver_version": "5.3.0"
  },
  "phase": {
    "id": "STEADY",
    "status": "COMPLETED",
    "duration_ms": 60000
  },
  "totals": {
    "requests": 100000,
    "errors": 0
  },
  "metrics": {
    "GET": {
      "requests": 80000,
      "latency": {
        "unit": "us",
        "summary": {"p50": 150, "p99": 450},
        "hdr": {"payload_b64": "..."}
      }
    }
  }
}
```

## Development

### Adding a New Driver

1. Create a new client implementation in `lib/resp_bench/client/impl/`
2. Implement the `BenchmarkClient` interface
3. Register the driver in `BenchmarkClientFactory::DRIVERS`
4. Create a driver configuration in `configs/drivers/`

### Adding a New Command

1. Create a new command implementation in `lib/resp_bench/command/impl/`
2. Extend the `Command` base class
3. Implement the `execute` method
4. Register the command in `CommandFactory::COMMAND_CLASSES`

## Dependencies

- `redis` (~> 5.0) - redis-rb client
- `valkey` (~> 0.1) - valkey-glide-ruby client
- `concurrent-ruby` - Thread-safe data structures
- `hdrhistogram` - Latency histograms
- `oj` - Fast JSON serialization

## License

Apache License 2.0 - see [LICENSE](../LICENSE)
