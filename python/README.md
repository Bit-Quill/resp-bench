# resp-bench Python Engine

🚧 **This engine is planned but not yet implemented.**

## Overview

Python implementation of the resp-bench benchmark suite.

## Planned Drivers

| Driver | Package | Status |
|--------|---------|--------|
| redis-py | `redis` | 📋 Planned |
| redis-py-async | `redis[hiredis]` | 📋 Planned |
| valkey-glide | `valkey-glide` | 📋 Planned |

## Planned Features

- Full parity with Java engine
- Async/await based execution using `asyncio`
- HdrHistogram for latency collection
- NDJSON metrics output

## Contributing

We welcome contributions to implement the Python engine! Please see:
- [Architecture Documentation](../docs/ARCHITECTURE.md)
- [Adding a Language Guide](../docs/ADDING_LANGUAGE.md)

## Directory Structure (Planned)

```
python/
├── README.md
├── pyproject.toml
├── requirements.txt
└── src/
    └── resp_bench/
        ├── __init__.py
        ├── __main__.py
        ├── client/
        │   ├── __init__.py
        │   ├── interface.py
        │   └── impl/
        │       └── redis_py.py
        ├── command/
        ├── config/
        ├── engine/
        └── metrics/
```

## Usage (Future)

```bash
# Install
pip install -e .

# Run benchmark
python -m resp_bench \
  --server localhost:6379 \
  --driver ../configs/drivers/example-redis-py-standalone.json \
  --workload ../configs/workloads/example-workload.json \
  --metrics output.ndjson

# Show supported drivers
python -m resp_bench --info
```
