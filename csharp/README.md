# resp-bench C# Engine

C# (.NET 10) implementation of the resp-bench benchmark suite.

## Supported Drivers

| Driver | Package | Description |
|--------|---------|-------------|
| stackexchange-redis | [StackExchange.Redis](https://github.com/StackExchange/StackExchange.Redis) | Most popular .NET Redis client |
| valkey-glide-csharp | [valkey-glide-csharp](https://github.com/valkey-io/valkey-glide-csharp) | Valkey GLIDE C# client (planned) |

## Prerequisites

- .NET 10.0 SDK or later

## Build

```bash
make csharp-build
# or
cd csharp && dotnet build -c Release
```

## Run Tests

```bash
make csharp-test
# or
cd csharp && dotnet test
```

## Run Benchmark

```bash
make csharp-run \
  DRIVER=configs/drivers/example-stackexchange-redis-standalone.json \
  WORKLOAD=configs/workloads/example-workload.json \
  SERVER=localhost:6379
```

## Show Supported Drivers

```bash
make csharp-info
```

## Architecture

The C# engine uses a **Task-per-client** architecture equivalent to Java's Virtual Thread-per-client:

- One long-lived `Task.Run()` per client connection
- `await` for I/O operations (equivalent to Java's VT `join()` which parks the VT)
- No per-request Task creation overhead
- `ConcurrentDictionary` + `Interlocked` for thread-safe metrics
- Pipeline depth > 1 uses `Task.WhenAny()` (equivalent to Java's `CompletableFuture.anyOf()`)
- `Stopwatch.GetTimestamp()` for microsecond-precision timing
- `SynchronizedHistogram` from HdrHistogram.NET for latency capture

## Configuration

Same JSON configuration files as all other engines. See [CONFIG_SPECIFICATION.md](../docs/CONFIG_SPECIFICATION.md).

## Author

Authored by Ilia Kolominsky
