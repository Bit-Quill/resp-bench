# resp-bench Python Engine

Python implementation of the resp-bench benchmark suite, at parity with the
Java (reference), Ruby, and C# engines.

## Supported Drivers

| Driver | `driver_id` | Package | Notes |
|--------|-------------|---------|-------|
| Valkey GLIDE | `valkey-glide-python` | `valkey-glide` (`import glide`) | Async client |
| redis-py | `redis-py` | `redis` (`redis.asyncio`) | Async client |
| valkey-py | `valkey-py` | `valkey` (`valkey.asyncio`) | Async client (Valkey fork of redis-py) |
| Recording | `recording` | — | In-memory; for server-free tests |

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

`pipeline_depth > 1` (multiple in-flight requests per connection) is not yet
implemented.

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
