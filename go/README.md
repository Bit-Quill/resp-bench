# resp-bench Go Engine

Go implementation of the resp-bench benchmark engine. Produces NDJSON metrics
byte-compatible with the Java/Ruby/C#/Node/PHP engines so the same workloads and
driver configs drive every engine identically.

## Supported Drivers

| Driver ID          | Library                              | Status                 |
|--------------------|--------------------------------------|------------------------|
| `recording`        | in-memory (server-free)              | ✅ implemented          |
| `go-redis`         | `github.com/redis/go-redis/v9`       | ✅ implemented          |
| `valkey-glide-go`  | Valkey GLIDE Go client               | 🚧 stub — needs wiring |

The `recording` driver runs the full engine path (key generation, command
selection, metrics, NDJSON output) without a server and is used by the test
suite. `go-redis` is a working driver (RESP3, retries disabled, one socket per
connection). `valkey-glide-go` is a stub that **fails loudly on connect** until
its client library is wired in (see below) — it never silently reports success.

## Supported Commands

`get`, `set`, `ping`.

## Build & Test

```bash
make go-build     # -> go/bin/resp-bench
make go-test      # go test ./...
make go-info      # list supported drivers
```

Or directly:

```bash
cd go
go build -o bin/resp-bench ./cmd/resp-bench
go test ./...
```

## Usage

```bash
make go-run \
  DRIVER=configs/drivers/default/valkey-glide-go.json \
  WORKLOAD=configs/workloads/reference/basic-standalone-single-client-10-secs.json \
  SERVER=localhost:6379 \
  METRICS_OUTPUT=output/go.ndjson
```

```bash
./go/bin/resp-bench \
  --server localhost:6379 \
  --driver  configs/drivers/default/go-redis.json \
  --workload configs/workloads/example-workload.json \
  --metrics output/go.ndjson \
  --commit-id "$(git rev-parse HEAD)"
```

Exit code: `0` if every phase completed, `1` if any phase failed (a worker
errored), `2` for usage/config errors.

## Architecture

```
go/
├── cmd/resp-bench/main.go        # CLI entry point
└── internal/
    ├── config/    # driver + workload JSON parsing (reference defaults)
    ├── client/    # BenchmarkClient interface, factory, recording + real-driver stubs
    ├── command/   # GET/SET/PING (+ weighted selection helpers)
    ├── engine/    # JavaRandom, KeyGenerator, RateLimiter, CommandSelector, Benchmark
    └── metrics/   # HdrHistogram, V2 encoder, Collector, NdjsonWriter
```

Concurrency model: one goroutine per connection. For a request-based phase the
budget is a single shared atomic counter claimed one request at a time (not
pre-divided per worker), so a slow worker never leaves the target unmet. Each
worker records into its own lock-free collector; collectors are merged after the
phase, widening the window to `min(start)`/`max(stop)`.

## Cross-Engine Parity

Parity traps this engine handles (see `docs/ADDING_LANGUAGE.md`):

- **JavaRandom**: `java.util.Random`'s 48-bit LCG, including the int32-overflow
  rejection branch in `nextInt`. Go's `int64` holds the multiply exactly, so no
  BigInt workaround is needed. Anchored by test: `Random(0).nextInt(1000)` →
  `[360, 948, 29, 447, 515, …]`.
- **Key generation**: `sequential_int` uses a counter **shared** across workers
  (a single atomic), so N workers partition the keyspace with no duplication;
  `uniform_rand` uses a **per-worker** PRNG seeded `base_seed + worker_index`.
  Keys are zero-padded to `key_size_bytes` (`%0Nd`, `N = max(1, size - prefix)`).
- **PING** does not consume a generated key.
- **HdrHistogram**: 1µs–600s, 3 sig figs. `min`/`max` return the bucket
  equivalent bounds (`ValueAtPercentile(0)`/`(100)`), matching Java's
  `getMinValue()`/`getMaxValue()`, not raw samples.
- **HDR V2 encoding**: `payload_length` is the **counts-array length only**
  (measured after the 40-byte header), or Java's decoder rejects it. The
  inflated V2 payload is byte-for-byte identical to the PHP engine's for the
  same samples (verified during development).
- **Shared request budget** and **rate-limit split** across active workers with
  the remainder distributed; a rps limit below the connection count activates
  only `rps_limit` workers instead of flooring each to 1 (which would overshoot).
- **Fail-loud**: unsupported knobs (`pipeline_depth > 1`, `cps_limit`,
  `command_timeout_ms`) are rejected; a worker failure marks the phase `ERROR`
  and returns a non-zero exit code; empty `metrics` serialize as `{}`.

## Wiring a real driver

`go-redis` is fully implemented in `internal/client/goredis.go` (RESP3, retries
disabled so a failure is recorded as an error rather than a retried success, one
pooled socket per connection). Run the gated live test against a server with:

```bash
VALKEY_HOST=localhost VALKEY_PORT=6379 go test ./internal/engine/ -run GoRedis
```

`valkey-glide-go` is still a stub in `internal/client/realdrivers.go`. To wire it:

1. Add the Valkey GLIDE Go module to `go/go.mod`.
2. Hold the client handle in `GlideClient`.
3. Implement `Connect`, `Get`, `Set`, `Ping`, `Close`, and `DriverVersion`,
   timing each call yourself (mirror `GoRedisClient.measure`) and returning the
   latency in the `TimedResult`. Keep the `TimedResult` contract identical —
   `Success()` is `Err == nil`, `LatencyMicros` in microseconds.
4. The gated live test pattern in `goredis_live_test.go` (skip unless
   `VALKEY_HOST` is set) can be copied for the GLIDE driver.
