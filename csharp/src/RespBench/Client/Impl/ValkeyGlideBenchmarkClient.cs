/*
 * Copyright 2025 the original author or authors.
 *
 * Valkey GLIDE C# client implementation.
 * Based on https://github.com/valkey-io/valkey-glide-csharp
 * NuGet: Valkey.Glide v0.9.0
 *
 * Valkey.Glide uses a StackExchange.Redis-compatible API
 * (ConnectionMultiplexer, IDatabase, IServer), so the implementation
 * mirrors StackExchangeRedisBenchmarkClient closely.
 */
using System.Diagnostics;
using RespBench.Config;
using Valkey.Glide;

namespace RespBench.Client.Impl;

/// <summary>
/// Valkey GLIDE C# implementation of IBenchmarkClient.
/// Uses the StackExchange.Redis-compatible API provided by Valkey.Glide.
/// </summary>
public class ValkeyGlideBenchmarkClient : IBenchmarkClient
{
    private IConnectionMultiplexer? _connection;
    private IDatabase? _db;
    private IServer? _server;
    private volatile bool _connected;

    public string DriverId => "valkey-glide-csharp";
    public string Description => "Valkey GLIDE C# high-performance client";
    public bool IsConnected => _connected;

    public string DriverVersion
    {
        get
        {
            try
            {
                return typeof(ConnectionMultiplexer).Assembly.GetName().Version?.ToString() ?? "0.9.0";
            }
            catch
            {
                return "0.9.0";
            }
        }
    }

    public void Connect(string host, int port, DriverConfig driverConfig)
    {
        try
        {
            var connectionString = $"{host}:{port}";

            _connection = ConnectionMultiplexer.ConnectAsync(connectionString).GetAwaiter().GetResult();
            _db = _connection.GetDatabase();

            var endpoints = _connection.GetEndPoints();
            if (endpoints.Length > 0)
                _server = _connection.GetServer(endpoints[0]);

            // Test connection
            _server?.PingAsync().GetAwaiter().GetResult();

            _connected = true;
            Console.WriteLine($"ValkeyGlide C# connected successfully to {host}:{port}");
        }
        catch (Exception e)
        {
            throw new ClientException($"Failed to connect ValkeyGlide C# to {host}:{port}: {e.Message}", e);
        }
    }

    public async Task<TimedResult<object?>> Set(byte[] key, byte[] value)
    {
        long start = Stopwatch.GetTimestamp();
        await _db!.StringSetAsync((ValkeyKey)key, (ValkeyValue)value).ConfigureAwait(false);
        long latencyMicros = GetElapsedMicros(start);
        return TimedResult<object?>.OfVoid(latencyMicros);
    }

    public async Task<TimedResult<byte[]?>> Get(byte[] key)
    {
        long start = Stopwatch.GetTimestamp();
        ValkeyValue result = await _db!.StringGetAsync((ValkeyKey)key).ConfigureAwait(false);
        long latencyMicros = GetElapsedMicros(start);
        return TimedResult<byte[]?>.Of((byte[]?)result, latencyMicros);
    }

    public async Task<TimedResult<string?>> Ping()
    {
        long start = Stopwatch.GetTimestamp();
        await _server!.PingAsync().ConfigureAwait(false);
        long latencyMicros = GetElapsedMicros(start);
        return TimedResult<string?>.Of("PONG", latencyMicros);
    }

    public Task<TimedResult<string?>> Ping(byte[] message) => Ping();

    public async Task<TimedResult<long>> Del(params byte[][] keys)
    {
        long start = Stopwatch.GetTimestamp();
        var valkeyKeys = keys.Select(k => (ValkeyKey)k).ToArray();
        long count = await _db!.KeyDeleteAsync(valkeyKeys).ConfigureAwait(false);
        long latencyMicros = GetElapsedMicros(start);
        return TimedResult<long>.Of(count, latencyMicros);
    }

    public async Task<TimedResult<object?>> FlushDb()
    {
        long start = Stopwatch.GetTimestamp();
        await _db!.ExecuteAsync("FLUSHDB").ConfigureAwait(false);
        long latencyMicros = GetElapsedMicros(start);
        return TimedResult<object?>.OfVoid(latencyMicros);
    }

    public void Dispose()
    {
        _connected = false;
        try { _connection?.Dispose(); } catch { }
        _connection = null;
        _db = null;
        _server = null;
    }

    private static long GetElapsedMicros(long startTimestamp)
    {
        long elapsed = Stopwatch.GetTimestamp() - startTimestamp;
        return elapsed * 1_000_000 / Stopwatch.Frequency;
    }
}
