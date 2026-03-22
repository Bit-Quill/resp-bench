using System.Text.Json;
using FluentAssertions;
using RespBench.Client.Impl;
using RespBench.Config;
using RespBench.Engine;
using Xunit;

namespace RespBench.Tests.Integration;

/// <summary>
/// Black-box integration tests that validate rate limiting functionality (cps_limit and rps_limit).
/// Ported from Java's RateLimitingTest.java.
/// </summary>
[Collection("RecordingClient")]
public class RateLimitingTest : IDisposable
{
    private const string Host = "localhost";
    private const int Port = 6379;
    private const double RateTolerancePercent = 5.0;

    public RateLimitingTest() => RecordingBenchmarkClient.ClearInstances();
    public void Dispose() => RecordingBenchmarkClient.ClearInstances();

    private static string RecordingDriverJson() =>
        """{"driver_id": "recording", "mode": "standalone"}""";

    [Fact]
    public async Task RpsLimitControlsRequestRate()
    {
        int targetRps = 20;
        int durationSeconds = 3;
        int expectedRequests = targetRps * durationSeconds;

        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "RpsLimitTest"},
            "phases": [{
                "id": "RPS_LIMITED", "connections": 1, "rps_limit": {{targetRps}},
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "rps:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "duration", "seconds": {{durationSeconds}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port,
            ConfigLoader.ParseDriverConfig(RecordingDriverJson()), workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        long totalRequests = json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64();
        long durationMs = json.RootElement.GetProperty("phase").GetProperty("duration_ms").GetInt64();

        double actualRate = totalRequests / (durationMs / 1000.0);
        actualRate.Should().BeApproximately(targetRps, targetRps * RateTolerancePercent / 100.0);
        totalRequests.Should().BeCloseTo(expectedRequests, (ulong)(expectedRequests * RateTolerancePercent / 100.0));

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task RpsLimitWithRequestBasedCompletion()
    {
        int targetRps = 20;
        int targetRequests = 60;
        long expectedDurationMs = (targetRequests * 1000L) / targetRps;

        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "RpsRequestBasedTest"},
            "phases": [{
                "id": "RPS_REQ_BASED", "connections": 1, "rps_limit": {{targetRps}},
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "rpsreq:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{targetRequests}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port,
            ConfigLoader.ParseDriverConfig(RecordingDriverJson()), workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        long totalRequests = json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64();
        long durationMs = json.RootElement.GetProperty("phase").GetProperty("duration_ms").GetInt64();

        totalRequests.Should().Be(targetRequests);
        durationMs.Should().BeCloseTo(expectedDurationMs, (ulong)(expectedDurationMs * RateTolerancePercent / 100.0));

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task MultipleConnectionsWithSharedRpsLimit()
    {
        int targetRps = 20;
        int connections = 4;
        int durationSeconds = 3;

        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "MultiConnRpsTest"},
            "phases": [{
                "id": "MULTI_CONN_RPS", "connections": {{connections}}, "rps_limit": {{targetRps}},
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "mc:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "duration", "seconds": {{durationSeconds}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port,
            ConfigLoader.ParseDriverConfig(RecordingDriverJson()), workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        long totalRequests = json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64();
        long durationMs = json.RootElement.GetProperty("phase").GetProperty("duration_ms").GetInt64();
        int actualConnections = json.RootElement.GetProperty("phase").GetProperty("connections").GetInt32();

        actualConnections.Should().Be(connections);
        double actualRate = totalRequests / (durationMs / 1000.0);
        actualRate.Should().BeApproximately(targetRps, targetRps * RateTolerancePercent / 100.0);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task CpsLimitControlsConnectionRate()
    {
        int targetCps = 10;
        int connections = 20;
        int requests = 10;
        long expectedConnectionTimeMs = (connections * 1000L) / targetCps;

        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "CpsLimitTest"},
            "phases": [{
                "id": "CPS_LIMITED", "connections": {{connections}}, "cps_limit": {{targetCps}},
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "cps:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{requests}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        long startTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var engine = new BenchmarkEngine(Host, Port,
            ConfigLoader.ParseDriverConfig(RecordingDriverJson()), workload, metricsFile);
        await engine.Run();
        long totalWallClockMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - startTime;

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        long totalRequests = json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64();

        totalRequests.Should().Be(requests);
        totalWallClockMs.Should().BeGreaterOrEqualTo((long)(expectedConnectionTimeMs * 0.95));

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task NoRateLimitAllowsMaximumThroughput()
    {
        int targetRequests = 1000;

        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "NoRateLimitTest"},
            "phases": [{
                "id": "NO_LIMIT", "connections": 1, "rps_limit": -1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "nl:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{targetRequests}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port,
            ConfigLoader.ParseDriverConfig(RecordingDriverJson()), workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        long totalRequests = json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64();
        long durationMs = json.RootElement.GetProperty("phase").GetProperty("duration_ms").GetInt64();

        totalRequests.Should().Be(targetRequests);
        durationMs.Should().BeLessThan(1000);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task CombinedCpsAndRpsLimitsWorkTogether()
    {
        int targetCps = 10;
        int targetRps = 20;
        int connections = 10;
        int targetRequests = 40;
        long expectedRequestTimeMs = (targetRequests * 1000L) / targetRps;

        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "CombinedLimitsTest"},
            "phases": [{
                "id": "COMBINED", "connections": {{connections}}, "cps_limit": {{targetCps}}, "rps_limit": {{targetRps}},
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "comb:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{targetRequests}}}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine(Host, Port,
            ConfigLoader.ParseDriverConfig(RecordingDriverJson()), workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        long totalRequests = json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64();
        long requestDurationMs = json.RootElement.GetProperty("phase").GetProperty("duration_ms").GetInt64();

        totalRequests.Should().Be(targetRequests);
        requestDurationMs.Should().BeCloseTo(expectedRequestTimeMs, (ulong)(expectedRequestTimeMs * RateTolerancePercent / 100.0));

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task NoRateLimitMuchFasterThanRateLimited()
    {
        int targetRequests = 100;
        int rpsLimit = 20;

        // Run 1: rate-limited
        var workload1 = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "RateLimitedComparison"},
            "phases": [{
                "id": "LIMITED", "connections": 1, "rps_limit": {{rpsLimit}},
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "cmp:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{targetRequests}}}
            }]
        }
        """);
        var metricsFile1 = Path.GetTempFileName();
        var engine1 = new BenchmarkEngine(Host, Port,
            ConfigLoader.ParseDriverConfig(RecordingDriverJson()), workload1, metricsFile1);
        await engine1.Run();

        var json1 = JsonDocument.Parse(File.ReadAllText(metricsFile1).Trim());
        long rateLimitedDurationMs = json1.RootElement.GetProperty("phase").GetProperty("duration_ms").GetInt64();

        RecordingBenchmarkClient.ClearInstances();

        // Run 2: unlimited
        var workload2 = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "UnlimitedComparison"},
            "phases": [{
                "id": "UNLIMITED", "connections": 1, "rps_limit": -1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "cmp:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": {{targetRequests}}}
            }]
        }
        """);
        var metricsFile2 = Path.GetTempFileName();
        var engine2 = new BenchmarkEngine(Host, Port,
            ConfigLoader.ParseDriverConfig(RecordingDriverJson()), workload2, metricsFile2);
        await engine2.Run();

        var json2 = JsonDocument.Parse(File.ReadAllText(metricsFile2).Trim());
        long unlimitedDurationMs = json2.RootElement.GetProperty("phase").GetProperty("duration_ms").GetInt64();

        // Rate limited should take ~5 seconds, unlimited much faster (10x+)
        long expectedRateLimitedMs = (targetRequests * 1000L) / rpsLimit;
        rateLimitedDurationMs.Should().BeCloseTo(expectedRateLimitedMs, (ulong)(expectedRateLimitedMs * RateTolerancePercent / 100.0));
        unlimitedDurationMs.Should().BeLessThan(rateLimitedDurationMs / 10);

        File.Delete(metricsFile1);
        File.Delete(metricsFile2);
    }
}
