# resp-bench Rust Engine

Rust implementation of the resp-bench benchmark suite. It parses the same driver
and workload JSON configs as the other engines and emits the same NDJSON metrics
schema, so a workload runs identically across Java (the reference), Python, Go,
Ruby, C#, PHP, Node.js, and Rust.

## Supported Drivers

| Driver id | Package | Notes |
|-----------|---------|-------|
| `valkey-glide-rust` | `glide` (valkey-glide native Rust client) | Blocking `sync` client, multiplexed over one socket (`sockets_per_client = 1` at any depth) |
| `redis-rs` | `redis` crate + `r2d2` pool | Pooling client, one socket per in-flight slot (`sockets_per_client = pipeline_depth`) |
| `recording` | — | Server-free, in-memory driver for tests |

The GLIDE client's APIs mirror redis-rs. This engine uses its **blocking (`sync`)
layer**, which drives the async client on a shared Tokio runtime — a natural fit
for the thread-per-worker execution model below. The `redis-rs` driver is the
published `redis` crate driven through an `r2d2` connection pool: a sync
`redis::Connection` is single-threaded, so real `pipeline_depth` concurrency
needs one connection per in-flight slot (the same model as Python `redis-py` /
Node `ioredis`), which is why it reports `pipeline_depth` sockets per client.

## Concurrency model

**One client per connection**, driven by `pipeline_depth` worker **threads** that
share that connection. Raising `pipeline_depth` adds in-flight requests without
changing which keys a connection issues, because the connection owns its key
generator and command selector (shared, `Arc`-wrapped, across its depth workers).
This mirrors the Java reference's virtual-thread workers and the Python/Go
engines.

A request-based phase target is a single shared atomic budget (`AtomicI64`)
claimed one request at a time, so a slow connection cannot cap the phase — faster
workers absorb the slack and the phase ends exactly at the target with no
overshoot. Duration-based phases use a wall-clock deadline.

GLIDE multiplexes, so `pipeline_depth > 1` puts that many requests on the one
socket: `sockets_per_client` is `1` at any depth. A pooling driver would instead
report `pipeline_depth` sockets; the NDJSON records which via `sockets_per_client`
/ `total_sockets` and a `pipelining` metadata field.

## Cross-engine parity

- **JavaRandom**: a faithful `java.util.Random` port, including the int32
  rejection branch (seed-0 anchor `[360, 948, 29, 447, 515, 53, 491, 761, 719,
  854]`), so `uniform_rand` key sequences match every other engine.
- **Key generation**: `sequential_int` uses a counter shared across a phase's
  workers; `uniform_rand` a per-worker PRNG seeded `base_seed + worker_index`.
  Keys are zero-padded to `max(key_size_bytes - prefix.len(), 1)` digits.
- **PING does not consume a key** (so it cannot shift the key stream).
- **HdrHistogram** `(1, 600_000_000, 3)`; the NDJSON `payload_b64` is the base64
  **V2 + DEFLATE** encoding, decodable by the Python `hdrh` analysis path (and
  Java/Ruby). `summary.min`/`max` use the bucket-equivalent
  `value_at_percentile(0/100)`, matching Java's `getMinValue`/`getMaxValue`.
- **Statuses** `COMPLETED` / `ERROR` / `INTERRUPTED` (SIGINT/SIGTERM stop the
  current phase gracefully). Ordinary command errors are recorded, not fatal; a
  phase is `ERROR` only on a worker panic or when every request failed. Anything
  short of `COMPLETED` yields a non-zero exit.
- **Honored knobs**: `pipeline_depth`, `cps_limit`, `rps_limit`,
  `warmup_requests`, `command_timeout_ms` (a driver-config field, per the shared
  schema), `tls`, `auth`.

## Toolchain

The GLIDE Rust client declares a minimum supported Rust version of **1.94.1**
(its AWS SDK dependencies require it). `rust-toolchain.toml` pins that channel, so
`cargo` selects the right compiler automatically.

## Build & run

```bash
# From the repo root
make rust-build        # cargo build --release
make rust-info         # list supported drivers/commands

make rust-run \
  SERVER=localhost:6379 \
  DRIVER=configs/drivers/default/valkey-glide-rust.json \
  WORKLOAD=configs/workloads/example-workload.json \
  METRICS_OUTPUT=results/rust.ndjson
```

Or directly:

```bash
cd rust
cargo run --release -- \
  --server localhost:6379 \
  --driver ../configs/drivers/default/valkey-glide-rust.json \
  --workload ../configs/workloads/example-workload.json \
  --metrics ../results/rust.ndjson
```

## Tests

```bash
make rust-test                                   # unit + server-free integration
cd rust && VALKEY_HOST=localhost \
  cargo test --release -- --ignored              # live GLIDE tests (need a server)
```

## Layout

```
rust/
├── Cargo.toml
├── rust-toolchain.toml          # pins GLIDE's MSRV
├── src/
│   ├── main.rs                  # CLI (+ SIGINT/SIGTERM → graceful interrupt)
│   ├── lib.rs
│   ├── config/                  # driver/workload/phase/keyspace/... + loader
│   ├── client/                  # BenchmarkClient trait, factory, impl_/{glide,redis_rs,recording}
│   ├── command/                 # GET/SET/PING + factory (PING ignores the key)
│   ├── engine/                  # benchmark, java_random, key_generator, rate_limiter, command_selector
│   └── metrics/                 # hdr (V2+DEFLATE), collector, ndjson_writer
└── tests/                       # engine (recording) + glide_live (ignored)
```

## Adding another driver

Implement `BenchmarkClient` (and, for a pooling driver, override
`set_max_in_flight`/`prime`/`sockets_per_client`), register it in
`src/client/factory.rs`, and time each call yourself. Keep the `TimedResult`
contract identical — `success` is "no error", `latency_micros` in microseconds.
