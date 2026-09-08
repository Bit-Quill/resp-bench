# resp-bench Python Engine

Python implementation of the resp-bench benchmark suite, at parity with the
Java (reference), Ruby, and C# engines.

## Supported Drivers

| Driver | `driver_id` | Package | Notes |
|--------|-------------|---------|-------|
| Valkey GLIDE | `valkey-glide-python` | `valkey-glide` (`import glide`) | Async client |
| redis-py | `redis-py` | `redis` (`redis.asyncio`) | Async client, RESP3, retries disabled |
| Recording | `recording` | — | In-memory; for server-free tests |

Peer drivers are kept deliberately few so each one's configuration can be held
equivalent (same RESP version, same retry policy, same command timeout). A
`valkey-py` driver is a planned follow-up.

> The GLIDE `driver_id` is `valkey-glide-python` (not the bare `valkey-glide`,
> which is the Java driver) — matching the `valkey-glide-ruby` /
> `valkey-glide-csharp` convention.

## Execution model

The engine is asyncio-based. For a phase with `connections = N`, it creates
**N client instances** (one client per connection — the `client == connection`
invariant shared by every engine) and runs **N worker coroutines** concurrently
on a single event loop. Each worker awaits one command at a time, i.e.
`pipeline_depth = 1` — the faithful async analogue of the Java/Ruby
"one in-flight request per connection" model, keeping results comparable across
engines.

This one-client-per-connection mapping is this engine's baseline; it is not a
property of the whole suite (among other engines' drivers, `lettuce` and
`redis-rb` are 1:1, but `jedis`/`redisson` pool, `spring-data-*` share a
template, and `stackexchange-redis` multiplexes). Sharing a single multiplexing client across
workers was proposed and declined upstream
([ikolomi/resp-bench#11](https://github.com/ikolomi/resp-bench/issues/11)) in
favour of keeping this baseline.

### Known limits

- **`pipeline_depth > 1`** (multiple in-flight requests per connection) is not
  implemented; such a phase runs at depth 1 and logs a warning.
- **Single event loop.** Above ~128 connections the event loop, not the driver,
  becomes the bottleneck, and loop queuing delay is attributed to the driver in
  the reported latency. The engine warns past that threshold. The Java engine hit
  the same ceiling with one command-issuing thread and added multiple issuer
  threads; this engine has no equivalent yet, so high-connection-count Python
  numbers are not directly comparable to other engines.

## Installation

```bash
pip install -e .
# with test tooling:
pip install -e ".[dev]"
```

## Usage

```bash
python -m resp_bench \
  --server localhost:6379 \
  --driver ../configs/drivers/default/redis-py.json \
  --workload ../configs/workloads/example-workload.json \
  --metrics output.ndjson

# Show supported drivers and commands
python -m resp_bench --info
```

## Testing

```bash
pytest                      # unit + recording-driver integration (no server needed)
```

See [../docs/ADDING_LANGUAGE.md](../docs/ADDING_LANGUAGE.md) and
[../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) for the shared contracts.
