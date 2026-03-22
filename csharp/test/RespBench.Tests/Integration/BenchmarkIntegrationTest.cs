using FluentAssertions;
using RespBench.Client;
using RespBench.Config;
using Xunit;

namespace RespBench.Tests.Integration;

/// <summary>
/// Integration tests that require a running Valkey/Redis server.
/// Ported from Java's BenchmarkIntegrationTest.java.
///
/// Server endpoint configured via environment variables:
///   VALKEY_HOST (default: localhost)
///   VALKEY_PORT (default: 6379)
///
/// Run with: make csharp-integration-test
/// </summary>
[Trait("Category", "Integration")]
public class BenchmarkIntegrationTest
{
    private static readonly string Host = Environment.GetEnvironmentVariable("VALKEY_HOST") ?? "localhost";
    private static readonly int Port = int.TryParse(Environment.GetEnvironmentVariable("VALKEY_PORT"), out var p) ? p : 6379;

    [Fact]
    public void StackExchangeRedisClientShouldConnect()
    {
        var config = ConfigLoader.ParseDriverConfig("""
            {"driver_id": "stackexchange-redis", "mode": "standalone"}
            """);

        using var client = BenchmarkClientFactory.CreateAndConnect(Host, Port, config);

        client.IsConnected.Should().BeTrue();
        client.Ping().Result.Value.Should().Be("PONG");

        // Test SET/GET
        byte[] key = "csharp-integration-test-key"u8.ToArray();
        byte[] value = "test-value"u8.ToArray();
        client.Set(key, value).Wait();
        byte[]? result = client.Get(key).Result.Value;
        result.Should().Equal(value);

        // Cleanup
        client.Del(key).Wait();
    }

    [Fact(Skip = "valkey-glide-csharp NuGet package not yet available")]
    public void ValkeyGlideCsharpClientShouldConnect()
    {
        var config = ConfigLoader.ParseDriverConfig("""
            {"driver_id": "valkey-glide-csharp", "mode": "standalone"}
            """);

        using var client = BenchmarkClientFactory.CreateAndConnect(Host, Port, config);

        client.IsConnected.Should().BeTrue();
        client.Ping().Result.Value.Should().Be("PONG");

        // Test SET/GET
        byte[] key = "csharp-glide-integration-test-key"u8.ToArray();
        byte[] value = "test-value"u8.ToArray();
        client.Set(key, value).Wait();
        byte[]? result = client.Get(key).Result.Value;
        result.Should().Equal(value);

        // Cleanup
        client.Del(key).Wait();
    }

    [Fact]
    public void BenchmarkClientCapturesConnectionErrors()
    {
        var config = ConfigLoader.ParseDriverConfig("""
            {"driver_id": "stackexchange-redis", "mode": "standalone"}
            """);

        // Use a port that's unlikely to have a server
        int nonExistentPort = 59999;

        var act = () => BenchmarkClientFactory.CreateAndConnect("localhost", nonExistentPort, config);
        act.Should().Throw<Exception>();
    }
}
