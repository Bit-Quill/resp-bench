# Adding a New Language Engine

This guide explains how to add support for a new programming language to resp-bench.

## Prerequisites

Before adding a new language, ensure you understand:
- [Architecture](ARCHITECTURE.md) - Overall system design
- [Configuration Specification](CONFIG_SPECIFICATION.md) - Config format details
- Existing implementations (Java is the reference implementation)

## Step-by-Step Guide

### 1. Create Directory Structure

```bash
mkdir -p <language>/src/{client/impl,command/impl,config,engine,metrics}
```

Example for Python:
```
python/
├── README.md
├── pyproject.toml
└── src/
    └── resp_bench/
        ├── __init__.py
        ├── __main__.py
        ├── client/
        │   ├── __init__.py
        │   ├── interface.py
        │   └── impl/
        │       ├── __init__.py
        │       └── redis_py.py
        ├── command/
        ├── config/
        ├── engine/
        └── metrics/
```

### 2. Implement Configuration Parsing

Parse the JSON configuration files:

```python
# config/driver_config.py
@dataclass
class DriverConfig:
    schema_version: str
    description: str
    driver_id: str
    mode: str  # "standalone" | "cluster" | "sentinel"
    tls: Optional[TlsConfig] = None
    auth: Optional[AuthConfig] = None
    specific_driver_config: Dict[str, Any] = field(default_factory=dict)

# config/workload_config.py
@dataclass
class WorkloadConfig:
    schema_version: str
    benchmark_profile: BenchmarkProfile
    phases: List[PhaseConfig]
```

### 3. Implement Client Interface

Define the common interface all drivers must implement:

```python
# client/interface.py
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List, Optional

@dataclass
class TimedResult[T]:
    value: Optional[T]
    latency_us: int
    error: Optional[Exception] = None

class BenchmarkClient(ABC):
    @abstractmethod
    async def connect(self, servers: List[str], config: DriverConfig) -> None:
        """Establish connection to the server(s)."""
        pass
    
    @abstractmethod
    async def get(self, key: str) -> TimedResult[str]:
        """Execute GET command and return result with latency."""
        pass
    
    @abstractmethod
    async def set(self, key: str, value: bytes) -> TimedResult[None]:
        """Execute SET command and return result with latency."""
        pass
    
    @abstractmethod
    async def ping(self) -> TimedResult[str]:
        """Execute PING command and return result with latency."""
        pass
    
    @abstractmethod
    async def close(self) -> None:
        """Close the connection."""
        pass
```

### 4. Implement Key Generator

Must produce identical sequences as other implementations:

```python
# engine/key_generator.py
class KeyGenerator:
    def __init__(self, config: KeyspaceConfig):
        self.keys_count = config.keys_count
        self.key_prefix = config.key_prefix
        self.algorithm = config.generation_alg
        self.seed = config.seed
        self.counter = 0
        
        if self.algorithm == "uniform_rand":
            # Use xoshiro256 or equivalent for reproducibility
            self.rng = RandomGenerator(self.seed)
    
    def next_key(self) -> str:
        if self.algorithm == "sequential_int":
            key_num = self.counter % self.keys_count
            self.counter += 1
        else:  # uniform_rand
            key_num = self.rng.next_int(self.keys_count)
        
        return f"{self.key_prefix}{key_num}"
```

### 5. Implement Rate Limiter

Token bucket algorithm:

```python
# engine/rate_limiter.py
class RateLimiter:
    def __init__(self, rate_per_second: int):
        self.rate = rate_per_second
        self.tokens = 0.0
        self.last_update = time.monotonic()
        self.lock = asyncio.Lock()
    
    async def acquire(self) -> None:
        if self.rate <= 0:  # Unlimited
            return
        
        async with self.lock:
            now = time.monotonic()
            elapsed = now - self.last_update
            self.tokens = min(self.rate, self.tokens + elapsed * self.rate)
            self.last_update = now
            
            if self.tokens >= 1:
                self.tokens -= 1
            else:
                wait_time = (1 - self.tokens) / self.rate
                await asyncio.sleep(wait_time)
                self.tokens = 0
```

### 6. Implement Metrics Collector

Use HdrHistogram with consistent configuration:

```python
# metrics/collector.py
from hdrhistogram import HdrHistogram

class MetricsCollector:
    def __init__(self):
        self.command_metrics: Dict[str, CommandMetrics] = {}
    
    def record(self, command: str, latency_us: int, success: bool) -> None:
        if command not in self.command_metrics:
            # 1µs to 600s, 3 significant figures (must match the other engines:
            # Java/C#/Ruby all use a max of 600_000_000µs, not 1 hour)
            self.command_metrics[command] = CommandMetrics(
                histogram=HdrHistogram(1, 600000000, 3)
            )
        
        metrics = self.command_metrics[command]
        if success:
            metrics.histogram.record_value(latency_us)
            metrics.success_count += 1
        else:
            metrics.error_count += 1
```

### 7. Implement Benchmark Engine

Coordinates all components:

```python
# engine/benchmark.py
class BenchmarkEngine:
    async def run_phase(self, phase: PhaseConfig, driver_config: DriverConfig) -> PhaseMetrics:
        # 1. Create client connections
        clients = await self._create_clients(phase.connections, driver_config)
        
        # 2. Setup rate limiters
        rps_limiter = RateLimiter(phase.rps_limit)
        
        # 3. Setup metrics
        metrics = MetricsCollector()
        
        # 4. Run warmup
        await self._run_warmup(clients, phase.warmup_requests)
        
        # 5. Run workload
        start_time = datetime.utcnow()
        await self._run_workload(clients, phase, metrics, rps_limiter)
        end_time = datetime.utcnow()
        
        # 6. Cleanup
        for client in clients:
            await client.close()
        
        return PhaseMetrics(
            phase_id=phase.id,
            start_timestamp=start_time,
            finish_timestamp=end_time,
            metrics=metrics
        )
```

### 8. Implement Output Writer

NDJSON format:

```python
# metrics/writer.py
class NdjsonMetricsWriter:
    def write_phase(self, metrics: PhaseMetrics, output_path: str) -> None:
        output = {
            "phase": {
                "id": metrics.phase_id,
                "status": "COMPLETED",
                "start_timestamp": metrics.start_timestamp.isoformat() + "Z",
                "finish_timestamp": metrics.finish_timestamp.isoformat() + "Z",
                "duration_ms": int((metrics.finish_timestamp - metrics.start_timestamp).total_seconds() * 1000),
                "connections": metrics.connections
            },
            "totals": {
                "requests": metrics.total_requests,
                "errors": metrics.total_errors
            },
            "metrics": self._format_command_metrics(metrics)
        }
        
        with open(output_path, 'a') as f:
            f.write(json.dumps(output) + '\n')
```

### 9. Implement CLI Entry Point

```python
# __main__.py
import argparse

def main():
    parser = argparse.ArgumentParser(description='resp-bench Python engine')
    parser.add_argument('--server', required=True, help='Server address(es)')
    parser.add_argument('--driver', required=True, help='Driver config file')
    parser.add_argument('--workload', required=True, help='Workload config file')
    parser.add_argument('--metrics', required=True, help='Metrics output file')
    parser.add_argument('--info', action='store_true', help='Show supported drivers')
    
    args = parser.parse_args()
    
    if args.info:
        print_driver_info()
        return
    
    asyncio.run(run_benchmark(args))

if __name__ == '__main__':
    main()
```

### 10. Add Makefile Targets

Update the root Makefile:

```makefile
# Python Engine
python-build:
	cd python && pip install -e .

python-test:
	cd python && pytest

python-run: python-build
	python -m resp_bench \
		--server $(SERVER) \
		--driver $(DRIVER) \
		--workload $(WORKLOAD) \
		--metrics $(METRICS_OUTPUT)
```

### 11. Implement Client Drivers

For each driver (e.g., redis-py):

```python
# client/impl/redis_py.py
import redis.asyncio as redis
import time

class RedisPyClient(BenchmarkClient):
    def __init__(self):
        self.client = None
    
    async def connect(self, servers: List[str], config: DriverConfig) -> None:
        host, port = servers[0].split(':')
        self.client = redis.Redis(host=host, port=int(port))
    
    async def get(self, key: str) -> TimedResult[str]:
        start = time.perf_counter_ns()
        try:
            result = await self.client.get(key)
            latency_us = (time.perf_counter_ns() - start) // 1000
            return TimedResult(value=result.decode() if result else None, latency_us=latency_us)
        except Exception as e:
            latency_us = (time.perf_counter_ns() - start) // 1000
            return TimedResult(value=None, latency_us=latency_us, error=e)
```

### 12. Write Tests

```python
# tests/test_key_generator.py
def test_sequential_generator():
    config = KeyspaceConfig(keys_count=3, key_prefix="test:", generation_alg="sequential_int")
    gen = KeyGenerator(config)
    
    assert gen.next_key() == "test:0"
    assert gen.next_key() == "test:1"
    assert gen.next_key() == "test:2"
    assert gen.next_key() == "test:0"  # Wraps around

def test_random_generator_reproducible():
    config = KeyspaceConfig(keys_count=1000, key_prefix="test:", generation_alg="uniform_rand", seed=12345)
    gen1 = KeyGenerator(config)
    gen2 = KeyGenerator(config)
    
    # Same seed produces same sequence
    for _ in range(100):
        assert gen1.next_key() == gen2.next_key()
```

### 13. Document the Engine

Create `<language>/README.md`:

```markdown
# resp-bench Python Engine

Python implementation of the resp-bench benchmark suite.

## Supported Drivers

| Driver | Package | Description |
|--------|---------|-------------|
| redis-py | redis | Standard Redis client |
| aioredis | aioredis | Async Redis client (legacy) |
| valkey-glide | valkey-glide | Valkey GLIDE client |

## Installation

\`\`\`bash
pip install -e .
\`\`\`

## Usage

\`\`\`bash
python -m resp_bench --server localhost:6379 --driver ../configs/drivers/example.json --workload ../configs/workloads/example.json --metrics output.ndjson
\`\`\`
```

## Validation Checklist

Before submitting a new language engine:

- [ ] Config parsing handles all schema fields
- [ ] Key generator produces identical sequences (test with seed=12345)
- [ ] Rate limiter achieves target rates within 5% tolerance
- [ ] Metrics output matches NDJSON schema exactly
- [ ] HdrHistogram produces compatible base64 payloads
- [ ] All unit tests pass
- [ ] Integration tests pass against live server
- [ ] Documentation complete
- [ ] Makefile targets work correctly

## Cross-Language Validation

Run the same workload on all engines and compare:

```bash
# Java
make java-run WORKLOAD=configs/workloads/validation.json METRICS_OUTPUT=output/java.ndjson

# Python  
make python-run WORKLOAD=configs/workloads/validation.json METRICS_OUTPUT=output/python.ndjson

# Compare key metrics (should be statistically similar)
jq '.metrics.GET.latency.summary' output/java.ndjson output/python.ndjson
```
