# resp-bench Go Engine

Go implementation of the resp-bench benchmark engine. Produces NDJSON metrics
byte-compatible with the Java/Ruby/C#/Node/PHP engines so the same workloads and
driver configs drive every engine identically.

## Supported Drivers

| Driver ID          | Library                              | Status                 |
|--------------------|--------------------------------------|------------------------|
| `recording`        | in-memory (server-free)              | ✅ implemented          |
| `go-redis`         | `github.com/redis/go-redis/v9`       | ✅ implemented          |
| `valkey-glide-go`  | `github.com/valkey-io/valkey-glide/go/v2` | ✅ implemented     |

The `recording` driver runs the full engine path without a server and is used by
the unit tests. Both real drivers are fully implemented and validated against a
live Valkey server:
- **go-redis**: RESP3, retries disabled, a connection pool bounded to
  `pipeline_depth` (so `sockets_per_client == pipeline_depth`).
- **valkey-glide-go**: multiplexes all requests over a single socket, so
  `pipeline_depth` is satisfied natively and `sockets_per_client == 1` at any depth.

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
errored) or was interrupted, `2` for usage/config errors.

## Architecture

```
go/
├── cmd/resp-bench/main.go        # CLI entry point
└── internal/
    ├── config/    # driver + workload JSON parsing (reference defaults)
    ├── client/    # BenchmarkClient interface, factory, recording + go-redis + glide drivers
    ├── command/   # GET/SET/PING (+ weighted selection helpers)
    ├── engine/    # JavaRandom, KeyGenerator, RateLimiter, CommandSelector, Benchmark
    └── metrics/   # HdrHistogram, V2 encoder, Collector, NdjsonWriter
```

Concurrency model: one client per connection, driven by `pipeline_depth` worker
goroutines that share that connection (so raising the depth adds in-flight
requests without changing which keys the connection touches). For a request-based
phase the budget is a single shared atomic counter claimed one request at a time
(not pre-divided per worker), so a slow worker never leaves the target unmet. Each
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
- **pipeline_depth**: honored — `depth` worker goroutines per connection share
  one key generator, selector, and rate limiter, so raising the depth changes
  concurrency without changing the key stream. A pooling driver (go-redis) bounds
  its pool to the depth; a multiplexing driver (GLIDE) keeps one socket. The
  output records `pipeline_depth`, `sockets_per_client`, and `total_sockets`.
- **cps_limit**: honored — a leaky-bucket limiter throttles connection creation.
- **warmup**: exactly `warmup_requests` PINGs **per client**, before the clock,
  and a failed warmup PING fails the phase fast.
- **Statuses**: `COMPLETED` / `ERROR` / `INTERRUPTED` (SIGINT/SIGTERM stop the
  current phase gracefully). Anything short of `COMPLETED` yields a non-zero exit.
- **Fail-loud & robust**: a worker failure marks the phase `ERROR`; a phase where
  every request failed is `ERROR`; empty `metrics` serialize as `{}`.

## Drivers

Both real drivers are implemented and validated against a live Valkey server;
the gated live tests skip unless `VALKEY_HOST` is set:

```bash
VALKEY_HOST=localhost VALKEY_PORT=6379 go test ./internal/engine/ -run 'GoRedis|Glide'
```

- `internal/client/goredis.go` — go-redis (RESP3, retries disabled, pool bounded
  to `pipeline_depth`).
- `internal/client/glide.go` — Valkey GLIDE Go (multiplexed, one socket per client).

### Adding another driver

Implement `BenchmarkClient` (and optionally `PipelineAware` / `DetailedClient`),
register it in `internal/client/factory.go`, and time each call yourself (mirror
`GoRedisClient.measure`). Keep the `TimedResult` contract identical —
`Success()` is `Err == nil`, `LatencyMicros` in microseconds.

## Known issues

- **GLIDE OTel init race (upstream, worked around).** `valkey-glide-go` v2.5.3's
  `GetOtelInstance()` lazily initializes a process-global singleton without
  synchronization, which every command touches — so concurrent first use races.
  The Go engine forces that global to initialize once, single-threaded, via a
  `sync.Once` around the first `Ping` in `GlideClient.Connect` (connections are
  created serially), after which all concurrent callers only read the settled
  value. This is why `go test -race` is clean. If GLIDE fixes the singleton, the
  `glideOtelOnce` workaround in `internal/client/glide.go` can be removed.
