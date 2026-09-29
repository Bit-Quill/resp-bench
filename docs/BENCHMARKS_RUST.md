# Rust Benchmark Engine

Details of the Rust implementation of resp-bench. See [`rust/README.md`](../rust/README.md)
for build/run instructions and [ARCHITECTURE.md](ARCHITECTURE.md) for the
cross-engine design.

## Client library

The engine drives two client libraries:

- **Valkey GLIDE native Rust client** (`glide` crate, built on `glide-core`; its
  command APIs mirror redis-rs). It uses the client's blocking `sync` layer,
  which multiplexes all requests for one client over a single socket on a shared
  Tokio runtime (`valkey-glide-rust`, `sockets_per_client = 1`).
- **redis-rs** (the published `redis` crate) driven through an `r2d2` connection
  pool sized to `pipeline_depth` (`redis-rs`, `sockets_per_client =
  pipeline_depth`). A sync `redis::Connection` is single-threaded, so a pool is
  the honest representation of in-flight depth — the same model as Python
  `redis-py` and Node `ioredis`.

## Execution model

Thread-based, one OS thread per pipeline slot:

- One client per connection.
- `pipeline_depth` worker threads share each client; at depth 1 this is one
  in-flight request per connection (the Java reference's shape).
- A per-connection key generator and command selector are shared by that
  connection's depth workers, so depth changes concurrency, not the key stream.
- A single shared `AtomicI64` request budget is claimed one request at a time
  (exact target, no overshoot). Duration phases use a wall-clock deadline.
- Per-worker metrics collectors are merged after the phase with a
  `min(start)/max(stop)` window.

## Metrics

- HdrHistogram range `(1, 600_000_000, 3)`.
- NDJSON `payload_b64` is the base64 **V2 + DEFLATE** encoding, decodable by the
  Python `hdrh` path used by the graph scripts.
- `summary.min`/`max` are bucket-equivalent (`value_at_percentile(0/100)`),
  matching Java.
- Empty metrics serialize as `{}`.
- Additive fields: `pipeline_depth`, `sockets_per_client`, `total_sockets`, and
  driver `metadata` (`resp_protocol`, `response_parser`, `retries`, `pipelining`).

## Status and exit codes

`COMPLETED` / `ERROR` / `INTERRUPTED`. SIGINT/SIGTERM flip a shared flag the
workers poll, stopping the current phase gracefully and recording it as
`INTERRUPTED`. A phase is `ERROR` only on a worker panic or when every request
failed. Any non-`COMPLETED` phase makes the process exit non-zero so the matrix
runner does not score the cell as a good run.

## Supported drivers and commands

Drivers: `valkey-glide-rust`, `redis-rs` (r2d2-pooled), `recording` (server-free, for tests).
Commands: `ping`, `get`, `set`.

## Toolchain

Pinned to Rust `1.94.1` via `rust/rust-toolchain.toml` — the GLIDE client's MSRV.
