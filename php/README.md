# resp-bench PHP Engine

PHP implementation of the resp-bench benchmark suite for Redis/Valkey compatible databases.

## Supported Drivers

| Driver ID | Package | Description |
|-----------|---------|-------------|
| `valkey-glide-php` | [ext-valkey_glide](https://github.com/valkey-io/valkey-glide-php) | Valkey GLIDE PHP client — Rust-core client exposed as a native PHP extension, with a PHPRedis-compatible API |
| `recording` | (built-in) | In-memory driver for server-free tests and pipeline validation |

## Prerequisites

- PHP 8.2 or 8.3 (the GLIDE PHP extension's tested versions; the engine itself runs on 8.2+)
- `ext-pcntl` (for the multi-process concurrency model; standard on Linux/macOS CLI builds)
- `ext-json` (bundled with PHP)
- Composer (recommended) — or use the bundled minimal autoloader
- For live-server runs: the `valkey_glide` extension installed and enabled

### Installing the Valkey GLIDE PHP extension

The `valkey-glide-php` driver requires the `valkey_glide` PHP extension. Install it
via `pie`, PECL, or from source — see the
[upstream instructions](https://github.com/valkey-io/valkey-glide-php#installation-and-setup).
After installing, enable it in `php.ini`:

```ini
extension=valkey_glide
```

Verify:

```bash
php -m | grep valkey_glide
```

The `recording` driver needs neither the extension nor a server, so unit and
integration tests run without either.

## Installation

```bash
cd php
composer install
```

If Composer is unavailable, the CLI falls back to a bundled minimal PSR-4
autoloader (`php/vendor/autoload.php`), so `make php-run` / `make php-info` still work.

## Usage

### Command line

```bash
# Run a benchmark
php php/bin/resp-bench \
  --server localhost:6379 \
  --driver configs/drivers/example-valkey-glide-php-standalone.json \
  --workload configs/workloads/example-workload.json \
  --metrics output/php.ndjson

# Show supported drivers and commands (also reports extension/pcntl availability)
php php/bin/resp-bench --info

# Help
php php/bin/resp-bench --help
```

### Using Make (from project root)

```bash
make php-build            # composer install (or bundled autoloader)
make php-test             # unit tests
make php-integration-test # integration tests (server-free, recording driver)
make php-run \
  DRIVER=configs/drivers/example-valkey-glide-php-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json \
  SERVER=localhost:6379
```

## Architecture

The PHP engine follows the same architecture as the Java reference implementation:

```
src/
├── Client/            # Client interface + implementations
│   ├── BenchmarkClient.php         # Abstract base
│   ├── TimedResult.php
│   ├── Factory.php
│   └── Impl/
│       ├── ValkeyGlidePhpClient.php # ext-valkey_glide
│       └── RecordingClient.php      # in-memory, server-free
├── Command/           # GET / SET / PING commands + Factory
├── Config/            # JSON config parsing (Driver/Workload/Phase/…)
├── Engine/
│   ├── Benchmark.php                # Multi-process orchestrator
│   ├── CommandSelector.php          # Weighted selection
│   ├── JavaRandom.php               # Java-compatible LCG
│   ├── KeyGenerator.php
│   └── RateLimiter.php              # Leaky bucket
└── Metrics/
    ├── Collector.php                # Per-command metrics + merge
    ├── HdrHistogram.php             # Pure-PHP HdrHistogram
    ├── HdrEncoder.php               # V2 compressed (Java-compatible) encoding
    └── NdjsonWriter.php             # NDJSON output
```

## Concurrency model

The engine uses a **process-per-connection** model (the PHP analogue of Java's
virtual-thread-per-client and Ruby's thread-per-client):

- `connections = N` → fork **N worker processes** (capped at 256).
- Each worker connects to the server **after** forking — a connection is never
  inherited across a fork, which is required for correctness with the native
  extension.
- Each worker runs one in-flight request at a time against its own client
  (the `client == connection` invariant), matching the other engines so results
  are comparable.
- Workers stream partial metrics back to the parent over a `stream_socket_pair`;
  the parent reconstructs and **merges the HdrHistograms losslessly** (sparse
  bucket counts) before writing NDJSON.
- Phase-level `rps_limit` is divided across workers so the aggregate matches the
  target rate.

The chosen model is driven by PHP's runtime: the GLIDE extension is synchronous,
and the standard PHP build is non-thread-safe (NTS), which rules out
`ext-parallel`. `pcntl` multi-processing is the faithful, dependency-free option.

An **`inline`** mode (`--concurrency inline`) runs all connections sequentially in
a single process. It can't produce true concurrency with a blocking client, but
it exercises the full pipeline and is used for server-free tests. It is selected
automatically for the `recording` driver and when `pcntl` is unavailable.

## Cross-language parity

- **JavaRandom**: a port of `java.util.Random`'s 48-bit LCG, verified
  byte-identical to Java's canonical `new Random(0).nextInt()` sequence. PHP's
  lack of 64-bit integer overflow wraparound is handled with a 24-bit split
  multiply.
- **Key generation**: `sequential_int` walks the keyspace; `uniform_rand` seeds
  per worker as `seed + workerIndex` for reproducible-yet-distinct sequences.
  Key formatting uses `%0Nd` honoring `key_size_bytes`.
- **HdrHistogram**: range `(1, 600_000_000, 3)`; the V2 compressed base64 payload
  is byte-compatible with Java's `encodeIntoCompressedByteBuffer()`.
- **Rate limiter**: leaky bucket, constant spacing, no burst.

## Metrics output

NDJSON compatible with all other language engines:

```json
{
  "metadata": {"commit_id": "abc123", "timestamp": "2026-09-14T16:27:11Z", "driver_id": "valkey-glide-php", "primary_driver_version": "1.0.0"},
  "phase": {"id": "STEADY", "status": "COMPLETED", "duration_ms": 60000, "connections": 16},
  "totals": {"requests": 1000000, "errors": 0},
  "metrics": {
    "GET": {
      "requests": 800000,
      "errors": 0,
      "latency": {"unit": "us", "count": 800000, "summary": {"p50": 150, "p99": 450}, "hdr": {"format": "hdr", "sigfig": 3, "payload_b64": "..."}}
    }
  }
}
```

## Testing

```bash
# From php/ with composer-installed PHPUnit:
vendor/bin/phpunit                     # all tests
vendor/bin/phpunit --testsuite unit    # unit only
vendor/bin/phpunit --testsuite integration
```

### Test coverage (Java parity)

| Java Test | PHP Test | Coverage |
|-----------|----------|----------|
| `JavaRandomTest` | `tests/Unit/JavaRandomTest.php` | LCG determinism, Java seed-0 anchor |
| `KeyGeneratorTest` | `tests/Unit/KeyGeneratorTest.php` | Sequential/wrap, uniform-rand, formatting, JavaRandom parity |
| `ConfigLoaderTest` | `tests/Unit/ConfigLoaderTest.php` | Driver/workload parsing, defaults, cluster mode |
| `RateLimiterTest` | `tests/Unit/RateLimiterTest.php` | Leaky-bucket rate enforcement |
| — | `tests/Unit/CommandSelectorTest.php` | Weighted command distribution |
| `MetricsOutputTest` | `tests/Unit/HdrEncoderTest.php`, `tests/Integration/MetricsOutputTest.php` | HDR percentiles + V2 compressed encoding; NDJSON schema, exact request counts, latency accuracy |
| `RateLimitingTest` | `tests/Integration/RateLimitingTest.php` | RPS enforcement, shared limit across (concurrent) connections, unlimited throughput |
| `ErrorMetricsIntegrationTest` | `tests/Integration/ErrorMetricsTest.php` | Error-rate simulation, per-command error counts, errors excluded from latency histogram |
| `RecordingBenchmarkClientTest` | `tests/Integration/RecordingWorkloadTest.php` | Full pipeline → NDJSON schema, inline + process modes |
| `BenchmarkIntegrationTest` | `tests/Integration/LiveClientTest.php` | Live server: connect/ping/set-get, driver version, multi-process fork-then-connect (gated; skips without extension/server) |

## Adding a new driver

1. Create a client in `src/Client/Impl/` extending `BenchmarkClient`.
2. Register it in `Client\Factory::DRIVERS`.
3. Add driver configs under `configs/drivers/`.

## License

Apache License 2.0 — see [LICENSE](../LICENSE)
