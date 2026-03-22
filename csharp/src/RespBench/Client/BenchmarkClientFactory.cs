/*
 * Copyright 2025 the original author or authors.
 */
using RespBench.Client.Impl;
using RespBench.Config;
using Microsoft.Extensions.Logging;

namespace RespBench.Client;

/// <summary>
/// Factory for creating BenchmarkClient instances.
/// </summary>
public static class BenchmarkClientFactory
{
    private static readonly Dictionary<string, DriverInfo> DriverRegistry = new(StringComparer.OrdinalIgnoreCase);

    static BenchmarkClientFactory()
    {
        RegisterDriver("stackexchange-redis",
            "StackExchange.Redis client",
            () => new StackExchangeRedisBenchmarkClient());
        RegisterDriver("valkey-glide-csharp",
            "Valkey GLIDE C# high-performance client (skeleton - awaiting NuGet package)",
            () => new ValkeyGlideBenchmarkClient());
        RegisterDriver("recording",
            "Recording client for testing with configurable latency and error simulation",
            () => new RecordingBenchmarkClient());
    }

    /// <summary>Register a custom client implementation.</summary>
    public static void RegisterDriver(string driverId, string description, Func<IBenchmarkClient> factory)
    {
        DriverRegistry[driverId.ToLowerInvariant()] = new DriverInfo(driverId, description, factory);
    }

    /// <summary>Check if a driver is supported.</summary>
    public static bool IsSupported(string driverId) => DriverRegistry.ContainsKey(driverId.ToLowerInvariant());

    /// <summary>Create a new BenchmarkClient instance.</summary>
    public static IBenchmarkClient Create(DriverConfig driverConfig)
    {
        string driverId = driverConfig.DriverId.ToLowerInvariant();
        if (!DriverRegistry.TryGetValue(driverId, out var driverInfo))
            throw new ArgumentException(
                $"Unsupported driver: {driverConfig.DriverId}. Supported: {string.Join(", ", DriverRegistry.Keys)}");

        return driverInfo.Factory();
    }

    /// <summary>Create and connect a BenchmarkClient.</summary>
    public static IBenchmarkClient CreateAndConnect(string host, int port, DriverConfig driverConfig)
    {
        var client = Create(driverConfig);
        try
        {
            client.Connect(host, port, driverConfig);
            return client;
        }
        catch
        {
            client.Dispose();
            throw;
        }
    }

    /// <summary>Get all registered driver IDs.</summary>
    public static IReadOnlyCollection<string> GetRegisteredDriverIds() => DriverRegistry.Keys;

    /// <summary>Get information about all registered drivers.</summary>
    public static IReadOnlyList<DriverInfo> GetRegisteredDrivers() => DriverRegistry.Values.ToList();

    public record DriverInfo(string DriverId, string Description, Func<IBenchmarkClient> Factory);
}
