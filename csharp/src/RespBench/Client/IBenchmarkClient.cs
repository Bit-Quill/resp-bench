/*
 * Copyright 2025 the original author or authors.
 */
using RespBench.Config;

namespace RespBench.Client;

/// <summary>
/// Abstraction interface for benchmark clients.
/// Implementations provide access to different Redis/Valkey client libraries.
/// All latency-sensitive operations return Task&lt;TimedResult&gt; for async execution.
/// </summary>
public interface IBenchmarkClient : IDisposable
{
    /// <summary>Get the driver ID (e.g., "stackexchange-redis", "valkey-glide-csharp").</summary>
    string DriverId { get; }

    /// <summary>Get a description of this client.</summary>
    string Description { get; }

    /// <summary>Get the driver library version.</summary>
    string DriverVersion { get; }

    /// <summary>Get the secondary driver library version (for composite drivers).</summary>
    string? SecondaryDriverVersion => null;

    /// <summary>Initialize and connect to the server.</summary>
    void Connect(string host, int port, DriverConfig driverConfig);

    /// <summary>Check if the client is currently connected.</summary>
    bool IsConnected { get; }

    /// <summary>Execute a SET command with timing measurement.</summary>
    Task<TimedResult<object?>> Set(byte[] key, byte[] value);

    /// <summary>Execute a GET command with timing measurement.</summary>
    Task<TimedResult<byte[]?>> Get(byte[] key);

    /// <summary>Execute a PING command with timing measurement.</summary>
    Task<TimedResult<string?>> Ping();

    /// <summary>Execute a PING command with a message and timing measurement.</summary>
    Task<TimedResult<string?>> Ping(byte[] message);

    /// <summary>Execute a DEL command with timing measurement.</summary>
    Task<TimedResult<long>> Del(params byte[][] keys);

    /// <summary>Flush all keys from the current database.</summary>
    Task<TimedResult<object?>> FlushDb();

    /// <summary>Notify the client that the engine is entering/leaving warmup mode.</summary>
    void SetWarmupMode(bool warmup) { }

    /// <summary>Close the client connection and release resources.</summary>
    new void Dispose();
}

/// <summary>Exception thrown when client operations fail.</summary>
public class ClientException : Exception
{
    public ClientException(string message) : base(message) { }
    public ClientException(string message, Exception innerException) : base(message, innerException) { }
}
