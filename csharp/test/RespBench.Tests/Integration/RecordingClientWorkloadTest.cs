using System.Text.RegularExpressions;
using FluentAssertions;
using RespBench.Client.Impl;
using RespBench.Config;
using RespBench.Engine;
using Xunit;

namespace RespBench.Tests.Integration;

[Collection("RecordingClient")]
public class RecordingClientWorkloadTest : IDisposable
{
    public RecordingClientWorkloadTest() => RecordingBenchmarkClient.ClearInstances();
    public void Dispose() => RecordingBenchmarkClient.ClearInstances();

    [Fact]
    public async Task KeyPrefixIsAppliedToAllKeys()
    {
        var driver = ConfigLoader.ParseDriverConfig("""{"driver_id": "recording", "mode": "standalone"}""");
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "KeyPrefixTest"},
            "phases": [{
                "id": "KEY_PREFIX", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 64}],
                "keyspace": {"key_prefix": "myprefix:", "keys_count": 100, "key_size_bytes": 20, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 100}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var setOps = RecordingBenchmarkClient.GetAggregateOperations("SET");
        setOps.Should().HaveCount(100);
        foreach (var op in setOps)
            System.Text.Encoding.UTF8.GetString(op.Key!).Should().StartWith("myprefix:");

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task SequentialIntGeneratesSequentialKeys()
    {
        var driver = ConfigLoader.ParseDriverConfig("""{"driver_id": "recording", "mode": "standalone"}""");
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "SequentialKeysTest"},
            "phases": [{
                "id": "SEQUENTIAL", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "seq:", "keys_count": 1000, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 10}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var setOps = RecordingBenchmarkClient.GetAggregateOperations("SET");
        setOps.Should().HaveCount(10);

        var keyPattern = new Regex(@"seq:(\d+)");
        var indices = setOps.Select(op =>
        {
            var key = System.Text.Encoding.UTF8.GetString(op.Key!);
            var match = keyPattern.Match(key);
            return int.Parse(match.Groups[1].Value);
        }).ToList();

        for (int i = 0; i < indices.Count; i++)
            indices[i].Should().Be(i);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task DataSizeBytesProducesCorrectValueSize()
    {
        var driver = ConfigLoader.ParseDriverConfig("""{"driver_id": "recording", "mode": "standalone"}""");
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "DataSizeTest"},
            "phases": [{
                "id": "DATA_SIZE", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 128}],
                "keyspace": {"key_prefix": "data:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 20}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var setOps = RecordingBenchmarkClient.GetAggregateSetOperationsWithValues();
        setOps.Should().HaveCount(20);
        foreach (var op in setOps)
            op.Value!.Length.Should().Be(128);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task EmptyKeyPrefixProducesKeysWithoutPrefix()
    {
        var driver = ConfigLoader.ParseDriverConfig("""{"driver_id": "recording", "mode": "standalone"}""");
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "EmptyPrefixTest"},
            "phases": [{
                "id": "EMPTY_PREFIX", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "", "keys_count": 50, "key_size_bytes": 10, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 50}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var setOps = RecordingBenchmarkClient.GetAggregateOperations("SET");
        setOps.Should().HaveCount(50);
        foreach (var op in setOps)
        {
            string key = System.Text.Encoding.UTF8.GetString(op.Key!);
            key.Should().MatchRegex(@"^\d+$");
        }

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task KeyNumbersAreWithinKeysCountRange()
    {
        int keysCount = 100;
        var driver = ConfigLoader.ParseDriverConfig("""{"driver_id": "recording", "mode": "standalone"}""");
        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "KeyRangeTest"},
            "phases": [{
                "id": "KEY_RANGE", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "test:", "keys_count": {{keysCount}}, "key_size_bytes": 16, "generation_alg": "uniform_rand", "seed": 42},
                "completion": {"type": "requests", "requests": 500}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var setOps = RecordingBenchmarkClient.GetAggregateOperations("SET");
        setOps.Should().HaveCount(500);
        var keyPattern = new Regex(@"test:(\d+)");
        foreach (var op in setOps)
        {
            string key = System.Text.Encoding.UTF8.GetString(op.Key!);
            var match = keyPattern.Match(key);
            match.Success.Should().BeTrue();
            int keyIndex = int.Parse(match.Groups[1].Value);
            keyIndex.Should().BeInRange(0, keysCount - 1);
        }

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task SequentialIntWrapsAroundAtKeysCount()
    {
        int keysCount = 5;
        var driver = ConfigLoader.ParseDriverConfig("""{"driver_id": "recording", "mode": "standalone"}""");
        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "WrapAroundTest"},
            "phases": [{
                "id": "WRAP_AROUND", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "wrap:", "keys_count": {{keysCount}}, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "completion": {"type": "requests", "requests": 12}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var setOps = RecordingBenchmarkClient.GetAggregateOperations("SET");
        setOps.Should().HaveCount(12);

        var keyPattern = new Regex(@"wrap:(\d+)");
        var indices = setOps.Select(op =>
        {
            string key = System.Text.Encoding.UTF8.GetString(op.Key!);
            var match = keyPattern.Match(key);
            return int.Parse(match.Groups[1].Value);
        }).ToList();

        int[] expected = [0, 1, 2, 3, 4, 0, 1, 2, 3, 4, 0, 1];
        for (int i = 0; i < expected.Length; i++)
            indices[i].Should().Be(expected[i]);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task UniformRandGeneratesRandomDistribution()
    {
        int keysCount = 100;
        var driver = ConfigLoader.ParseDriverConfig("""{"driver_id": "recording", "mode": "standalone"}""");
        var workload = ConfigLoader.ParseWorkloadConfig($$"""
        {
            "benchmark_profile": {"name": "RandomDistTest"},
            "phases": [{
                "id": "RANDOM_DIST", "connections": 1,
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                "keyspace": {"key_prefix": "rand:", "keys_count": {{keysCount}}, "key_size_bytes": 16, "generation_alg": "uniform_rand", "seed": 12345},
                "completion": {"type": "requests", "requests": 1000}
            }]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var setOps = RecordingBenchmarkClient.GetAggregateOperations("SET");
        setOps.Should().HaveCount(1000);

        var keyCounts = new Dictionary<int, int>();
        var keyPattern = new Regex(@"rand:(\d+)");
        foreach (var op in setOps)
        {
            string key = System.Text.Encoding.UTF8.GetString(op.Key!);
            var match = keyPattern.Match(key);
            int keyIndex = int.Parse(match.Groups[1].Value);
            keyCounts[keyIndex] = keyCounts.GetValueOrDefault(keyIndex, 0) + 1;
        }

        keyCounts.Count.Should().BeGreaterThan(50);
        keyCounts.Values.Max().Should().BeLessThan(50);

        File.Delete(metricsFile);
    }

    [Fact]
    public async Task DifferentDataSizesAreRespected()
    {
        var driver = ConfigLoader.ParseDriverConfig("""{"driver_id": "recording", "mode": "standalone"}""");
        var workload = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "MultiDataSizeTest"},
            "phases": [
                {"id": "SIZE_32", "connections": 1, "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}], "keyspace": {"key_prefix": "s32:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"}, "completion": {"type": "requests", "requests": 5}},
                {"id": "SIZE_256", "connections": 1, "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 256}], "keyspace": {"key_prefix": "s256:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"}, "completion": {"type": "requests", "requests": 5}},
                {"id": "SIZE_1024", "connections": 1, "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 1024}], "keyspace": {"key_prefix": "s1024:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"}, "completion": {"type": "requests", "requests": 5}}
            ]
        }
        """);

        var metricsFile = Path.GetTempFileName();
        var engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile);
        await engine.Run();

        var allSetOps = RecordingBenchmarkClient.GetAggregateSetOperationsWithValues();
        allSetOps.Should().HaveCount(15);

        int count32 = 0, count256 = 0, count1024 = 0;
        foreach (var op in allSetOps)
        {
            string key = System.Text.Encoding.UTF8.GetString(op.Key!);
            if (key.StartsWith("s32:")) { op.Value!.Length.Should().Be(32); count32++; }
            else if (key.StartsWith("s256:")) { op.Value!.Length.Should().Be(256); count256++; }
            else if (key.StartsWith("s1024:")) { op.Value!.Length.Should().Be(1024); count1024++; }
        }
        count32.Should().Be(5);
        count256.Should().Be(5);
        count1024.Should().Be(5);

        File.Delete(metricsFile);
    }
}
