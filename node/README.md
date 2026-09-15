# resp-bench Node.js Engine

Node.js implementation of the resp-bench benchmark suite, written in TypeScript.

## Supported Drivers

| `driver_id` | Package | Notes |
|---|---|---|
| `valkey-glide-node` | [`@valkey/valkey-glide`](https://www.npmjs.com/package/@valkey/valkey-glide) | Valkey GLIDE for Node.js. Ships prebuilt native binaries per platform. |
| `ioredis` | [`ioredis`](https://www.npmjs.com/package/ioredis) | The most widely used Node.js Redis client. |
| `iovalkey` | [`iovalkey`](https://www.npmjs.com/package/iovalkey) | The Valkey-maintained fork of ioredis; API-identical. |
| `recording` | — | In-memory synthetic-latency client for server-free tests. |

> **Note:** the GLIDE driver id is `valkey-glide-node`, not `valkey-glide`. The
> latter is already the Java engine's id in the global `DRIVER_ENGINE_MAP` in
> `scripts/run_benchmark_matrix.py`, and reusing it would reroute Java's runs here.

## Requirements

- Node.js **20 or newer** (`package.json` declares `engines.node >= 20`). CI uses 22 LTS.

## Build

```bash
npm ci && npm run build     # or: make node-build
```

TypeScript compiles to `dist/`, mirroring the source tree, so the entry point is
`dist/src/cli.js`.

## Usage

```bash
node dist/src/cli.js \
  --server localhost:6379 \
  --driver ../configs/drivers/default/ioredis.json \
  --workload ../configs/workloads/reference/basic-standalone-single-client-1M-reqs.json \
  --metrics ../output/node.ndjson
```

Or through the Makefile, from the repository root:

```bash
make node-run \
  DRIVER=configs/drivers/default/valkey-glide-node.json \
  WORKLOAD=configs/workloads/reference/basic-standalone-single-client-1M-reqs.json \
  METRICS_OUTPUT=output/node.ndjson

make node-info      # list supported drivers and commands
```

## Tests

```bash
make node-test               # unit + integration (starts/stops a server)
cd node && npm run test:unit # unit only, no server needed
```

The live-server tests skip themselves unless `VALKEY_HOST` is set:

```bash
cd node && VALKEY_HOST=localhost VALKEY_PORT=6379 npm run test:integration
```

The `recording` driver lets the full engine — phases, warmup, budget, pipelining,
rate limiting, NDJSON — be exercised with no server at all.

## Concurrency Model

A single event loop with **one client per connection** and **one worker per
connection**, all started together. Node is single-threaded with async I/O, so an
awaited command parks that worker rather than the loop, and the other connections
keep making progress. This is the analogue of Java's
virtual-thread-per-client design.

`pipeline_depth > 1` is supported: each connection runs that many independent
issue/await/record slots, so a settled request is replaced immediately.

The per-phase request budget is **shared across all workers** and claimed one
request at a time, matching the Java reference's `AtomicLong`. It is deliberately
not pre-divided per worker — a shared budget lets fast connections absorb a slow
one's slack, so wall-clock is not bounded by the slowest connection.

## Cross-Engine Parity

Verified against the Java reference engine, not assumed:

- **Key sequences are byte-identical.** 79,000 keys were diffed against Java's
  actual `KeyGenerator` across both algorithms, 1–16 workers, prime `keys_count`,
  and tight padding. `src/engine/javaRandom.ts` is a `java.util.Random` port
  anchored to `new Random(0).nextInt() == -1155484576`; it uses `BigInt` because
  the 48-bit LCG multiply overflows a JS `number`.
- **HDR payloads are mutually decodable.** `payload_b64` is
  `encodeIntoCompressedBase64()` used directly (it is *already* base64 — encoding
  it again would produce something Java cannot read). Java's
  `Histogram.decodeFromCompressedByteBuffer` reads our payloads with matching
  count and percentiles. Range is `(1, 600_000_000, 3)`, as in every engine.
- **`summary.min`/`max` use `getValueAtPercentile(0/100)`**, not
  `minNonZeroValue`/`maxValue`. Java reports the bucket's equivalent bounds, and
  hdr-histogram-js' properties return the raw sample — they diverge above ~1000µs
  (recording 50000µs yields 50000 in JS but 50015 in Java).
- **PING does not consume a key**, matching Java's `PingCommand`, so mixing PING
  into a workload does not shift the key sequence.

## Node-Specific Fairness Notes

Read these before comparing Node numbers to another engine:

- **Auto-pipelining is explicitly disabled.** ioredis and iovalkey can
  transparently batch commands issued in the same event-loop tick, which would
  inflate throughput against every other engine. `enableAutoPipelining: false`.
- **Reconnects are disabled** (`retryStrategy: () => null`). ioredis otherwise
  retries forever, so a wrong host would hang a run instead of failing it, and a
  mid-phase reconnect would fold connection setup into request latency.
- **All drivers decode responses the same way.** `get` returns a `string` in all
  three, so none is charged for a different amount of decoding.
- **SET payloads are built once** per command object, not per request, so the
  driver is not charged for the engine's own allocation churn.
- **Sub-millisecond rate limits work.** `setTimeout` clamps to ~1 ms, so the
  limiter yields via `setImmediate` for shorter intervals; a 100k rps limit is a
  10 µs interval and a timer-based wait would undershoot it by ~100×.
- **RSS is not comparable to the JVM's.** The system monitor's memory samples
  include V8 heap growth and GC timing, which behave differently from the JVM's.
- **One event loop is one core.** At high connection counts the engine itself, not
  the client, may become the ceiling. See `docs/BENCHMARKS_NODE.md`.

## Layout

```
node/
├── src/
│   ├── cli.ts                 # arg parsing, --info, error boundary
│   ├── client/                # driver interface, factory, per-driver impls
│   ├── command/               # GET / SET / PING
│   ├── config/                # JSON config parsing + validation
│   ├── engine/                # benchmark loops, key gen, RNG, rate limiter
│   └── metrics/               # HDR histogram, collector, NDJSON writer
└── test/
    ├── unit/                  # no server, no optional deps
    └── integration/           # recording driver + live-server tests
```
