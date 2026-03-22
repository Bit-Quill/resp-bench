using FluentAssertions;
using RespBench.Config;
using Xunit;

namespace RespBench.Tests.Unit;

public class ConfigLoaderTest
{
    [Fact]
    public void ShouldParseDriverConfig()
    {
        var config = ConfigLoader.ParseDriverConfig("""{"driver_id": "jedis", "mode": "standalone"}""");
        config.DriverId.Should().Be("jedis");
        config.Mode.Should().Be("standalone");
        config.IsClusterMode.Should().BeFalse();
    }

    [Fact]
    public void ShouldDetectClusterMode()
    {
        var config = ConfigLoader.ParseDriverConfig("""{"driver_id": "jedis", "mode": "cluster"}""");
        config.IsClusterMode.Should().BeTrue();
    }

    [Fact]
    public void ShouldDetectTlsEnabled()
    {
        var config = ConfigLoader.ParseDriverConfig("""{"driver_id": "jedis", "mode": "standalone", "tls": {}}""");
        config.IsTlsEnabled.Should().BeTrue();
    }

    [Fact]
    public void ShouldThrowOnMissingDriverId()
    {
        var act = () => ConfigLoader.ParseDriverConfig("{}");
        act.Should().Throw<ConfigurationException>();
    }

    [Fact]
    public void ShouldLoadDriverConfigFromFile()
    {
        string json = """
        {
            "schema_version": "1.0",
            "driver_id": "stackexchange-redis",
            "mode": "standalone",
            "description": "Test"
        }
        """;
        var path = Path.GetTempFileName();
        File.WriteAllText(path, json);
        try
        {
            var config = ConfigLoader.LoadDriverConfig(path);
            config.DriverId.Should().Be("stackexchange-redis");
            config.Mode.Should().Be("standalone");
        }
        finally { File.Delete(path); }
    }

    [Fact]
    public void ShouldLoadWorkloadConfig()
    {
        string json = """
        {
            "schema_version": "1.0",
            "benchmark_profile": {"name": "Test"},
            "phases": [{
                "id": "PHASE1",
                "connections": 10,
                "completion": {"type": "duration", "seconds": 10},
                "keyspace": {"keys_count": 1000, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 100}]
            }]
        }
        """;
        var path = Path.GetTempFileName();
        File.WriteAllText(path, json);
        try
        {
            var config = ConfigLoader.LoadWorkloadConfig(path);
            config.Phases.Should().HaveCount(1);
            config.Phases[0].Id.Should().Be("PHASE1");
            config.Phases[0].Connections.Should().Be(10);
        }
        finally { File.Delete(path); }
    }

    [Fact]
    public void ShouldParseWorkloadConfig()
    {
        var config = ConfigLoader.ParseWorkloadConfig("""
        {
            "benchmark_profile": {"name": "Test"},
            "phases": [{
                "id": "P1", "connections": 1,
                "completion": {"type": "requests", "requests": 100},
                "keyspace": {"keys_count": 10, "key_size_bytes": 8, "generation_alg": "sequential_int"},
                "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}]
            }]
        }
        """);
        config.Phases.Should().HaveCount(1);
        config.Phases[0].Completion.IsRequestBased.Should().BeTrue();
        config.Phases[0].Completion.TotalRequests.Should().Be(100);
    }
}
