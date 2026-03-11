# resp-bench Java Engine

Java implementation of the resp-bench benchmark suite.

## Supported Drivers

| Driver | Description | Cluster Support |
|--------|-------------|-----------------|
| `jedis` | Jedis client | ✅ |
| `lettuce` | Lettuce reactive client | ✅ |
| `valkey-glide` | Valkey GLIDE client | ✅ |
| `redisson` | Redisson async client | ✅ |
| `spring-data-valkey` | Spring Data Valkey | ✅ |
| `spring-data-redis` | Spring Data Redis | ✅ |

## Requirements

- Java 17 or later
- Maven 3.6 or later

## Building

```bash
# From repo root
make java-build

# Or directly
cd java && mvn clean package -DskipTests
```

## Running

```bash
# From repo root
make java-run \
  DRIVER=configs/drivers/example-jedis-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json

# Or directly
java -jar target/resp-bench-java-1.0.0-SNAPSHOT.jar \
  --server localhost:6379 \
  --driver ../configs/drivers/example-jedis-standalone.json \
  --workload ../configs/workloads/example-workload.json \
  --metrics ../output/metrics.ndjson
```

## CLI Options

```
Usage: resp-bench [-hV] [--info] [--commit-id=<commitId>]
                  [--command-issuer-threads=<N>] -d=<driverConfig>
                  -m=<metricsOutput> -s=<servers> -w=<workloadConfig>
      --command-issuer-threads=<N>
                      Number of parallel command issuer threads
                        (default: auto = max(1, connections/32), capped at
                        available processors)
      --commit-id=<commitId>
                      Git commit ID for metadata (auto-detected from build)
  -d, --driver=<driverConfig>
                      Path to driver configuration JSON
  -h, --help          Show this help message and exit.
      --info          Show supported drivers and commands
  -m, --metrics=<metricsOutput>
                      Path for metrics output file (NDJSON)
  -s, --server=<servers>
                      Server address(es), e.g., localhost:6379 or
                        host1:6379,host2:6379
  -V, --version       Print version information and exit.
  -w, --workload=<workloadConfig>
                      Path to workload configuration JSON
```

> **Note**: The `--commit-id` is auto-detected during Maven build from git. Override only when needed.

### Parallel Command Issuers

At high connection counts (128+), a single command-issuing thread can become a CPU bottleneck. The `--command-issuer-threads` flag controls the number of parallel threads that generate and submit commands.

By default, the engine auto-detects an appropriate count: `max(1, connections / 32)`, capped at available processors. For most workloads, the default is optimal. Override only if you observe the command-issuer thread saturating a CPU core in profiling (e.g., via `pidstat -t`).

```bash
# Example: force 8 command issuer threads for 256 connections
java -jar target/resp-bench-java-1.0.0-SNAPSHOT.jar \
  --server localhost:6379 \
  --driver ../configs/drivers/example-valkey-glide-standalone.json \
  --workload ../configs/workloads/example-workload.json \
  --metrics ../output/metrics.ndjson \
  --command-issuer-threads 8
```

## Testing

```bash
# Unit tests
make java-test

# Integration tests (requires running server)
make java-integration-test
```

## Supported Commands

| Command | Description |
|---------|-------------|
| `ping` | PING command |
| `get` | GET key |
| `set` | SET key value |

## Architecture

```
io.valkey.javabenchmark
├── client/           # Client implementations
│   ├── BenchmarkClient.java
│   ├── BenchmarkClientFactory.java
│   └── impl/
│       ├── JedisBenchmarkClient.java
│       ├── LettuceBenchmarkClient.java
│       ├── ValkeyGlideBenchmarkClient.java
│       ├── RedissonBenchmarkClient.java
│       ├── SpringDataValkeyBenchmarkClient.java
│       └── SpringDataRedisBenchmarkClient.java
├── command/          # Command implementations
│   ├── Command.java
│   ├── CommandFactory.java
│   └── impl/
│       ├── SetCommand.java
│       ├── GetCommand.java
│       └── PingCommand.java
├── config/           # Configuration models
│   ├── DriverConfig.java
│   ├── WorkloadConfig.java
│   └── ConfigLoader.java
├── engine/           # Benchmark engine
│   ├── BenchmarkEngine.java
│   ├── KeyGenerator.java
│   └── RateLimiter.java
├── metrics/          # Metrics collection
│   ├── MetricsCollector.java
│   └── NdjsonMetricsWriter.java
└── Main.java         # CLI entry point
```

## Adding New Drivers

1. Implement `BenchmarkClient` interface:

```java
public class MyClient implements BenchmarkClient {
    @Override
    public void connect(List<String> servers, DriverConfig config) { ... }
    
    @Override
    public CompletableFuture<TimedResult<String>> get(String key) { ... }
    
    @Override
    public CompletableFuture<TimedResult<Void>> set(String key, byte[] value) { ... }
    
    @Override
    public CompletableFuture<TimedResult<String>> ping() { ... }
    
    @Override
    public void close() { ... }
}
```

2. Register in `BenchmarkClientFactory`:

```java
DRIVERS.put("my-client", new DriverInfo(
    "My Client",
    "Description",
    MyClient::new
));
```

## License

Apache License 2.0
