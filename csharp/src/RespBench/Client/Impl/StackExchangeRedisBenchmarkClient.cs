/*
 * Copyright 2025 the original author or authors.
 */
using System.Diagnostics;
using System.Reflection;
using RespBench.Config;
using StackExchange.Redis;

namespace RespBench.Client.Impl;

/// <summary>
/// StackExchange.Redis implementation of IBenchmarkClient.
/// </summary>
public class StackExchangeRedisBenchmarkClient : IBenchmarkClient
{
    private ConnectionMultiplexer? _connection;
    private IDatabase? _db;
    private volatile bool _connected;

    public string DriverId => "stackexchange-redis";
    public string Description => "StackExchange.Redis client";
    public bool IsConnected => _connected;

    public string DriverVersion
    {
        get
        {
            try
            {
                var asm = typeof(ConnectionMultiplexer).Assembly;
                return asm.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
                    ?? asm.GetName().Version?.ToString() ?? "unknown";
            }
            catch { return "unknown"; }
        }
    }

    public void Connect(string host, int port, DriverConfig driverConfig)
    {
        var options = new ConfigurationOptions
        {
            EndPoints = { { host, port } },
            AbortOnConnectFail = true,
            ConnectRetry = 3,
            ConnectTimeout = 10000,
            AllowAdmin = true, // For FlushDb
        };

        // Configure TLS
        if (driverConfig.IsTlsEnabled)
        {
            options.Ssl = true;
            options.SslProtocols = System.Security.Authentication.SslProtocols.Tls12 | System.Security.Authentication.SslProtocols.Tls13;
        }

        // Configure authentication
        if (driverConfig.HasAuth)
        {
            if (!string.IsNullOrEmpty(driverConfig.Auth?.Username))
                options.User = driverConfig.Auth.Username;
            options.Password = driverConfig.Auth!.Password;
        }

        // Configure command timeout
        if (driverConfig.CommandTimeoutMs.HasValue)
        {
            options.SyncTimeout = driverConfig.CommandTimeoutMs.Value;
            options.AsyncTimeout = driverConfig.CommandTimeoutMs.Value;
        }

        // Apply specific driver config
        if (driverConfig.SpecificDriverConfig != null)
        {
            // StackExchange.Redis specific options can be added here
            // e.g., sync_timeout, async_timeout, etc.
        }

        _connection = ConnectionMultiplexer.Connect(options);
        _db = _connection.GetDatabase();

        // Test connection
        var pong = _db.Ping();
        if (pong.TotalMilliseconds <= 0 && pong.TotalMilliseconds > 30000)
            throw new ClientException("Connection test failed");

        _connected = true;
    }

    public Task<TimedResult<object?>> Set(byte[] key, byte[] value)
    {
        return AsyncHelper.TimedVoid(() =>
        {
            _db!.StringSet((RedisKey)key, (RedisValue)value);
        });
    }

    public Task<TimedResult<byte[]?>> Get(byte[] key)
    {
        return AsyncHelper.Timed<byte[]?>(() =>
        {
            RedisValue result = _db!.StringGet((RedisKey)key);
            return result.IsNullOrEmpty ? null : (byte[])result!;
        });
    }

    public Task<TimedResult<string?>> Ping()
    {
        return AsyncHelper.Timed<string?>(() =>
        {
            _db!.Ping();
            return "PONG";
        });
    }

    public Task<TimedResult<string?>> Ping(byte[] message)
    {
        return Ping(); // StackExchange.Redis Ping doesn't support message
    }

    public Task<TimedResult<long>> Del(params byte[][] keys)
    {
        return AsyncHelper.Timed(() =>
        {
            var redisKeys = keys.Select(k => (RedisKey)k).ToArray();
            return _db!.KeyDelete(redisKeys);
        });
    }

    public Task<TimedResult<object?>> FlushDb()
    {
        return AsyncHelper.TimedVoid(() =>
        {
            var server = _connection!.GetServer(_connection.GetEndPoints()[0]);
            server.FlushDatabase();
        });
    }

    public void Dispose()
    {
        _connected = false;
        _connection?.Dispose();
        _connection = null;
        _db = null;
    }
}
