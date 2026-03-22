using System.Text.Json;
using FluentAssertions;
using RespBench.Client.Impl;
using RespBench.Config;
using RespBench.Engine;
using Xunit;

namespace RespBench.Tests.Integration;

[Collection("RecordingClient")]
public class ErrorMetricsIntegrationTest : IDisposable
{
    public ErrorMetricsIntegrationTest() => RecordingBenchmarkClient.ClearInstances();
    public void Dispose() => RecordingBenchmarkClient.ClearInstances();

    [Fact]
    public async Task SimulatedErrorsAreCapturedInMetrics()
    {
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {"error_rate": 0.1, "error_message": "Simulated 10% error"}
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "ErrorSimTest"},
            "phases": [{
                "id": "ERROR_SIM", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "err:", "keys_count": 1000, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 10000}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        long totalReqs = json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64();
        long errorCount = json.RootElement.GetProperty("totals").GetProperty("errors").GetInt64();

        totalReqs.Should().Be(10000);
        double errorRate = (double)errorCount / totalReqs;
        errorRate.Should().BeInRange(0.08, 0.12);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task FullErrorRateProducesAllErrors()
    {
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {"error_rate": 1.0}
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "FullErrorTest"},
            "phases": [{
                "id": "ALL_ERRORS", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "ae:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 100}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64().Should().Be(100);
        json.RootElement.GetProperty("totals").GetProperty("errors").GetInt64().Should().Be(100);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task ZeroErrorRateProducesNoErrors()
    {
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {"error_rate": 0.0}
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "NoErrorTest"},
            "phases": [{
                "id": "NO_ERRORS", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "ne:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 100}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("errors").GetInt64().Should().Be(0);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task ErrorsAreTrackedPerCommand()
    {
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {"error_rate": 0.2}
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "PerCommandErrorTest"},
            "phases": [{
                "id": "MULTI_CMD_ERRORS", "connections": 1,
                "commands": [
                    {"command": "set", "weight": 0.5, "data_size_bytes": 32},
                    {"command": "get", "weight": 0.5}
                ],
                "keyspace": {"key_prefix": "percmd:", "keys_count": 1000, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 20000}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64().Should().Be(20000);

        long setRequests = json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("requests").GetInt64();
        long setErrors = json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("errors").GetInt64();
        long getRequests = json.RootElement.GetProperty("metrics").GetProperty("GET").GetProperty("requests").GetInt64();
        long getErrors = json.RootElement.GetProperty("metrics").GetProperty("GET").GetProperty("errors").GetInt64();

        setRequests.Should().BeInRange(9600, 10400);
        getRequests.Should().BeInRange(9600, 10400);

        double setErrorRate = (double)setErrors / setRequests;
        setErrorRate.Should().BeInRange(0.18, 0.22);
        double getErrorRate = (double)getErrors / getRequests;
        getErrorRate.Should().BeInRange(0.18, 0.22);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task DurationBasedCompletionWithErrors()
    {
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {"error_rate": 0.1, "operation_delay_micros": 100}
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "DurationErrorTest"},
            "phases": [{
                "id": "DURATION_TEST", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "dur:", "keys_count": 1000, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "duration", "seconds": 2}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        long requests = json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64();
        long errors = json.RootElement.GetProperty("totals").GetProperty("errors").GetInt64();

        requests.Should().BeGreaterThan(5000);
        double errorRate = (double)errors / requests;
        errorRate.Should().BeInRange(0.08, 0.12);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task MetricsHandleEmptyErrorCollection()
    {
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {"error_rate": 0.0}
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "EmptyErrorTest"},
            "phases": [{
                "id": "EMPTY_ERRORS", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "empty:", "keys_count": 10, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 10}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("errors").GetInt64().Should().Be(0);
        json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("errors").GetInt64().Should().Be(0);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task MetricsHandleAllErrorsCollection()
    {
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {"error_rate": 1.0}
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "AllErrorsTest"},
            "phases": [{
                "id": "ALL_ERRORS_EDGE", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "allerr:", "keys_count": 10, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 10}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64().Should().Be(10);
        json.RootElement.GetProperty("totals").GetProperty("errors").GetInt64().Should().Be(10);
        json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("requests").GetInt64().Should().Be(10);
        json.RootElement.GetProperty("metrics").GetProperty("SET").GetProperty("errors").GetInt64().Should().Be(10);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task ErrorMessageIsPreservedInRecordedOperation()
    {
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {"error_rate": 1.0, "error_message": "Custom error message for testing"}
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "ErrorMessageTest"},
            "phases": [{
                "id": "ERROR_MSG_TEST", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "errmsg:", "keys_count": 10, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 5}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("errors").GetInt64().Should().Be(5);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task SuccessfulOperationsHaveNoErrors()
    {
        var driver = ConfigLoader.ParseDriverConfig("""
        {
            "driver_id": "recording", "mode": "standalone",
            "specific_driver_config": {"error_rate": 0.0}
        }
        """);
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "SuccessTest"},
            "phases": [{
                "id": "SUCCESS_TEST", "connections": 1,
                "commands": [
                    {"command": "set", "weight": 0.5, "data_size_bytes": 32},
                    {"command": "get", "weight": 0.5}
                ],
                "keyspace": {"key_prefix": "success:", "keys_count": 50, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 100}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var json = JsonDocument.Parse(File.ReadAllText(metricsFile).Trim());
        json.RootElement.GetProperty("totals").GetProperty("requests").GetInt64().Should().Be(100);
        json.RootElement.GetProperty("totals").GetProperty("errors").GetInt64().Should().Be(0);

        File.Delete(metricsFile);
    }

    [Fact]
    public void BenchmarkClientCapturesConnectionErrors()
    {
        var config = ConfigLoader.ParseDriverConfig("""{"driver_id": "stackexchange-redis", "mode": "standalone"}""");
        int nonExistentPort = 59999;

        var act = () => RespBench.Client.BenchmarkClientFactory.CreateAndConnect("localhost", nonExistentPort, config);
        act.Should().Throw<Exception>();
    }
}
