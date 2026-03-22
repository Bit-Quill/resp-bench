using System.Text.Json;
using FluentAssertions;
using RespBench.Client.Impl;
using RespBench.Config;
using RespBench.Engine;
using Xunit;

namespace RespBench.Tests.Integration;

/// <summary>
/// Black-box integration tests that validate NDJSON metrics output format and histogram accuracy.
/// Ported from Java's MetricsOutputTest.java.
///
/// Tests with allDrivers are parameterized using [Theory]+[MemberData] to run with
/// all available C# drivers, matching Java's @ParameterizedTest @MethodSource("allDrivers").
/// </summary>
[Collection("RecordingClient")]
public class MetricsOutputTest : IDisposable
{
    private static readonly string Host = Environment.GetEnvironmentVariable("VALKEY_HOST") ?? "localhost";
    private static readonly int Port = int.TryParse(Environment.GetEnvironmentVariable("VALKEY_PORT"), out var p) ? p : 6379;

    public MetricsOutputTest() => RecordingBenchmarkClient.ClearInstances();
    public void Dispose() => RecordingBenchmarkClient.ClearInstances();

    // === Driver Configurations for Parameterized Tests ===
    // C# equivalent of Java's allDrivers() — all available C# drivers
    // These tests require a live Valkey/Redis server on localhost:6379
    public static IEnumerable<object[]> AllDrivers()
    {
        yield return new object[] { "stackexchange-redis", """{"driver_id": "stackexchange-redis", "mode": "standalone"}""" };
        yield return new object[] { "valkey-glide-csharp", """{"driver_id": "valkey-glide-csharp", "mode": "standalone"}""" };
    }

    // === Parameterized NDJSON Format Tests (run with all drivers) ===

    [Theory]
    [MemberData(nameof(AllDrivers))]
    public async Task NdjsonOutputHasValidFormat(string driverName, string driverJson)
    {
        var driver = ConfigLoader.ParseDriverConfig(driverJson);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "FormatTest"},
            "phases": [{
                "id": "FORMAT_TEST", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "fmt:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 100}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        File.Exists(metricsFile).Should().BeTrue();
        var line = File.ReadAllText(metricsFile).Trim();
        line.Should().NotBeEmpty();
        line.Split('\n').Should().HaveCount(1);
        var json = JsonDocument.Parse(line);
        json.RootElement.ValueKind.Should().Be(JsonValueKind.Object);

        File.Delete(metricsFile);
    }

    [Theory]
    [MemberData(nameof(AllDrivers))]
    public async Task MultiplePhasesProduceMultipleLines(string driverName, string driverJson)
    {
        var driver = ConfigLoader.ParseDriverConfig(driverJson);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "MultiPhaseTest"},
            "phases": [
                {"id": "PHASE_1", "connections": 1, "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}], "keyspace": {"key_prefix": "p1:", "keys_count": 50, "key_size_bytes": 16, "generation_alg": "sequential_int"}, "completion": {"type": "requests", "requests": 50}},
                {"id": "PHASE_2", "connections": 1, "commands": [{"command": "get", "weight": 1.0}], "keyspace": {"key_prefix": "p1:", "keys_count": 50, "key_size_bytes": 16, "generation_alg": "sequential_int"}, "completion": {"type": "requests", "requests": 50}}
            ]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var lines = File.ReadAllLines(metricsFile).Where(l => !string.IsNullOrWhiteSpace(l)).ToArray();
        lines.Should().HaveCount(2);
        for (int i = 0; i < lines.Length; i++)
        {
            var json = JsonDocument.Parse(lines[i]);
            json.RootElement.GetProperty("phase").GetProperty("id").GetString().Should().Be($"PHASE_{i + 1}");
        }

        File.Delete(metricsFile);
    }

    [Theory]
    [MemberData(nameof(AllDrivers))]
    public async Task PhaseMetadataIsCorrect(string driverName, string driverJson)
    {
        var driver = ConfigLoader.ParseDriverConfig(driverJson);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "MetadataTest"},
            "phases": [{
                "id": "METADATA_TEST", "connections": 2,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "meta:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 100}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("phase").GetProperty("id").GetString().Should().Be("METADATA_TEST");
        json.RootElement.GetProperty("phase").GetProperty("status").GetString().Should().Be("COMPLETED");
        json.RootElement.GetProperty("phase").GetProperty("connections").GetInt32().Should().Be(2);
        json.RootElement.GetProperty("phase").GetProperty("duration_ms").GetInt64().Should().BeGreaterThan(0);

        File.Delete(metricsFile);
    }

    [Theory]
    [MemberData(nameof(AllDrivers))]
    public async Task TotalRequestCountMatchesExecution(string driverName, string driverJson)
    {
        var driver = ConfigLoader.ParseDriverConfig(driverJson);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "CountTest"},
            "phases": [{
                "id": "COUNT_TEST", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "cnt:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 1000}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64().Should().Be(1000);
        json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("requests").GetInt64().Should().Be(1000);

        File.Delete(metricsFile);
    }

    [Theory]
    [MemberData(nameof(AllDrivers))]
    public async Task HistogramCapturesLatency(string driverName, string driverJson)
    {
        var driver = ConfigLoader.ParseDriverConfig(driverJson);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "HistogramTest"},
            "phases": [{
                "id": "HIST_TEST", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "hist:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 1000}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        var summary = json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("latency").GetProperty("summary");
        long min = summary.GetProperty("min").GetInt64();
        long p50 = summary.GetProperty("p50").GetInt64();
        long p95 = summary.GetProperty("p95").GetInt64();
        long p99 = summary.GetProperty("p99").GetInt64();
        long max = summary.GetProperty("max").GetInt64();

        p50.Should().BeGreaterOrEqualTo(min);
        p95.Should().BeGreaterOrEqualTo(p50);
        p99.Should().BeGreaterOrEqualTo(p95);
        max.Should().BeGreaterOrEqualTo(p99);

        long errors = json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("errors").GetInt64();
        errors.Should().Be(0);

        long latencyCount = json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("latency").GetProperty("count").GetInt64();
        latencyCount.Should().Be(1000);

        File.Delete(metricsFile);
    }

    [Theory]
    [MemberData(nameof(AllDrivers))]
    public async Task HistogramBase64CanBeDecoded(string driverName, string driverJson)
    {
        var driver = ConfigLoader.ParseDriverConfig(driverJson);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "DecodeTest"},
            "phases": [{
                "id": "DECODE_TEST", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "dec:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 100}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        string base64Payload = json.RootElement.GetProperty("metrics").GetProperty("SET")
            .GetProperty("latency").GetProperty("hdr").GetProperty("payload_b64").GetString()!;
        base64Payload.Should().NotBeEmpty();
        var act = () => Convert.FromBase64String(base64Payload);
        act.Should().NotThrow();

        File.Delete(metricsFile);
    }

    [Theory]
    [MemberData(nameof(AllDrivers))]
    public async Task PerCommandMetricsAreAccurate(string driverName, string driverJson)
    {
        int targetRequests = 10000;
        var driver = ConfigLoader.ParseDriverConfig(driverJson);
        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "PerCommandTest"},
            "phases": [{
                "id": "MULTI_CMD", "connections": 10,
                "commands": [
                    {"command": "set", "weight": 0.5, "data_size_bytes": 32},
                    {"command": "get", "weight": 0.5}
                ],
                "keyspace": {"key_prefix": "percmd:", "keys_count": 200, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{targetRequests}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        long totalRequests = json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64();
        totalRequests.Should().Be(targetRequests);

        long setRequests = json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("requests").GetInt64();
        long getRequests = json.RootElement.GetProperty("metrics").GetProperty("GET").GetProperty("requests").GetInt64();
        (setRequests + getRequests).Should().Be(totalRequests);
        setRequests.Should().BeCloseTo(5000, 150);
        getRequests.Should().BeCloseTo(5000, 150);

        File.Delete(metricsFile);
    }

    [Theory]
    [MemberData(nameof(AllDrivers))]
    public async Task OutputContainsAllRequiredFields(string driverName, string driverJson)
    {
        var driver = ConfigLoader.ParseDriverConfig(driverJson);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "SchemaTest"},
            "phases": [{
                "id": "SCHEMA_TEST", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "s:", "keys_count": 10, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 20}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        var root = json.RootElement;

        root.TryGetProperty("phase", out _).Should().BeTrue();
        root.GetProperty("phase").TryGetProperty("id", out _).Should().BeTrue();
        root.GetProperty("phase").TryGetProperty("status", out _).Should().BeTrue();
        root.GetProperty("phase").TryGetProperty("duration_ms", out _).Should().BeTrue();
        root.GetProperty("phase").TryGetProperty("connections", out _).Should().BeTrue();
        root.TryGetProperty("totals", out _).Should().BeTrue();
        root.GetProperty("totals").TryGetProperty("requests", out _).Should().BeTrue();
        root.GetProperty("totals").TryGetProperty("errors", out _).Should().BeTrue();
        root.TryGetProperty("metrics", out _).Should().BeTrue();
        var latency = root.GetProperty("metrics").GetProperty("SET").GetProperty("latency");
        latency.TryGetProperty("unit", out _).Should().BeTrue();
        latency.TryGetProperty("summary", out _).Should().BeTrue();
        latency.TryGetProperty("hdr", out _).Should().BeTrue();
        latency.GetProperty("summary").TryGetProperty("p50", out _).Should().BeTrue();
        latency.GetProperty("summary").TryGetProperty("p99", out _).Should().BeTrue();
        latency.GetProperty("summary").TryGetProperty("p999", out _).Should().BeTrue();
        latency.GetProperty("hdr").TryGetProperty("payload_b64", out _).Should().BeTrue();

        File.Delete(metricsFile);
    }

    // === Recording-client-only tests (parallel issuers, long-tail) ===

    [Fact]
    public async Task ParallelIssuersProduceExactRequestCount()
    {
        int totalRequests = 10_000;
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {
                "latency_distribution": "log_normal", "latency_min_ms": 10,
                "latency_median_ms": 20, "latency_p9999_target_ms": 100
            }
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "ParallelExactCountTest"},
            "phases": [{
                "id": "PARALLEL_EXACT", "connections": 64,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "pex:", "keys_count": 1000, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{totalRequests}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64().Should().Be(totalRequests);
        json.RootElement.GetProperty("phase").GetProperty("status").GetString().Should().Be("COMPLETED");

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task ParallelIssuersWithFewerConnectionsThanThreads()
    {
        int totalRequests = 500;
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {
                "latency_distribution": "log_normal", "latency_min_ms": 5,
                "latency_median_ms": 10, "latency_p9999_target_ms": 50
            }
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "FewConnsTest"},
            "phases": [{
                "id": "FEW_CONNS", "connections": 2,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "fewconn:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{totalRequests}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64().Should().Be(totalRequests);
        json.RootElement.GetProperty("phase").GetProperty("status").GetString().Should().Be("COMPLETED");

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task SingleIssuerThreadProducesExactCount()
    {
        int totalRequests = 5_000;
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {
                "latency_distribution": "log_normal", "latency_min_ms": 5,
                "latency_median_ms": 15, "latency_p9999_target_ms": 80
            }
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "SingleIssuerTest"},
            "phases": [{
                "id": "SINGLE_ISSUER", "connections": 10,
                "commands": [
                    {"command": "set", "weight": 0.5, "data_size_bytes": 32},
                    {"command": "get", "weight": 0.5}
                ],
                "keyspace": {"key_prefix": "single:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{totalRequests}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile, null, 1);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64().Should().Be(totalRequests);
        json.RootElement.GetProperty("phase").GetProperty("status").GetString().Should().Be("COMPLETED");

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task ParallelIssuersWithDurationBasedCompletion()
    {
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {
                "latency_distribution": "log_normal", "latency_min_ms": 5,
                "latency_median_ms": 10, "latency_p9999_target_ms": 50
            }
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "ParallelDurationTest"},
            "phases": [{
                "id": "PARALLEL_DURATION", "connections": 32,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "pdur:", "keys_count": 500, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "duration", "seconds": 2}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64().Should().BeGreaterThan(0);
        json.RootElement.GetProperty("phase").GetProperty("status").GetString().Should().Be("COMPLETED");
        json.RootElement.GetProperty("phase").GetProperty("duration_ms").GetInt64().Should().BeGreaterOrEqualTo(1900);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task LatencyHistogramWithLongTailDistribution()
    {
        long latencyMinMs = 50;
        long medianMs = 150;
        long p9999TargetMs = 900;
        int totalRequests = 100_000;

        var driver = ConfigLoader.ParseDriverConfig($$"""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {
                "latency_distribution": "log_normal",
                "latency_min_ms": {{latencyMinMs}},
                "latency_median_ms": {{medianMs}},
                "latency_p9999_target_ms": {{p9999TargetMs}}
            }
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "LongTailTest"},
            "phases": [{
                "id": "LONG_TAIL", "connections": 1000,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "lt:", "keys_count": 10000, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{totalRequests}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64().Should().Be(totalRequests);

        // Verify histogram ordering
        var summary = json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("latency").GetProperty("summary");
        long p50 = summary.GetProperty("p50").GetInt64();
        long p99 = summary.GetProperty("p99").GetInt64();
        long max = summary.GetProperty("max").GetInt64();
        p50.Should().BeLessThanOrEqualTo(p99);
        p99.Should().BeLessThanOrEqualTo(max);

        // Verify base64 is decodable
        string base64 = json.RootElement.GetProperty("metrics").GetProperty("SET")
            .GetProperty("latency").GetProperty("hdr").GetProperty("payload_b64").GetString()!;
        base64.Should().NotBeEmpty();
        Convert.FromBase64String(base64).Length.Should().BeGreaterThan(0);

        File.Delete(metricsFile);
    }
}
