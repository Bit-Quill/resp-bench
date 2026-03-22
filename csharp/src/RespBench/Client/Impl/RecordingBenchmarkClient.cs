/*
 * Copyright 2025 the original author or authors.
 */
using System.Collections.Concurrent;
using System.Diagnostics;
using RespBench.Config;

namespace RespBench.Client.Impl;

/// <summary>
/// Recording client for testing. Records all operations for verification.
/// Supports configurable latency simulation and error injection.
/// </summary>
public class RecordingBenchmarkClient : IBenchmarkClient
{
    // Static registry for test access
    private static readonly ConcurrentBag<RecordingBenchmarkClient> Instances = new();
    private static readonly ConcurrentQueue<RecordedOperation> AggregateOps = new();

    private readonly ConcurrentQueue<RecordedOperation> _operations = new();
    private long _setCount, _getCount, _pingCount, _delCount;
    private readonly ConcurrentDictionary<string, byte[]> _data = new();
    private volatile bool _connected;

    // Delay simulation
    private long _operationDelayMicros;
    private long _delayVariationMicros;
    private string _latencyDistribution = "fixed";
    private long _latencyMinMs;
    private double _logNormalMu;
    private double _logNormalSigma;
    private readonly Random _random = new();

    // Error simulation
    private double _errorRate;
    private string _errorMessage = "Simulated error";
    private volatile bool _warmupMode;

    public string DriverId => "recording";
    public string Description => "Recording client for testing";
    public string DriverVersion => "1.0.0";
    public bool IsConnected => _connected;

    public void Connect(string host, int port, DriverConfig driverConfig)
    {
        _connected = true;

        if (driverConfig?.SpecificDriverConfig != null)
        {
            _operationDelayMicros = driverConfig.GetSpecificLong("operation_delay_micros", 0);
            _delayVariationMicros = driverConfig.GetSpecificLong("delay_variation_micros", 0);
            _errorRate = driverConfig.GetSpecificDouble("error_rate", 0.0);
            var errMsg = driverConfig.GetSpecificString("error_message");
            if (errMsg != null) _errorMessage = errMsg;

            var dist = driverConfig.GetSpecificString("latency_distribution");
            if (dist != null) _latencyDistribution = dist;

            if (_latencyDistribution == "log_normal")
            {
                _latencyMinMs = driverConfig.GetSpecificLong("latency_min_ms", 0);
                long medianMs = driverConfig.GetSpecificLong("latency_median_ms", 150);
                long p9999TargetMs = driverConfig.GetSpecificLong("latency_p9999_target_ms", 900);
                _logNormalMu = Math.Log(medianMs);
                _logNormalSigma = (Math.Log(p9999TargetMs) - _logNormalMu) / 3.72;
                if (_logNormalSigma <= 0) _logNormalSigma = 0.5;
            }
        }

        Record("CONNECT", null, null, true, null);
        Instances.Add(this);
    }

    public void SetWarmupMode(bool warmup) => _warmupMode = warmup;

    public async Task<TimedResult<object?>> Set(byte[] key, byte[] value)
    {
        Interlocked.Increment(ref _setCount);
        long start = Stopwatch.GetTimestamp();
        await SimulateDelay().ConfigureAwait(false);
        long latencyMicros = GetElapsedMicros(start);
        bool success = !ShouldSimulateError();

        if (success) _data[System.Text.Encoding.UTF8.GetString(key)] = value;
        Record("SET", key, value, success, success ? null : _errorMessage);
        if (!success) throw new InvalidOperationException(_errorMessage);
        return TimedResult<object?>.OfVoid(latencyMicros);
    }

    public async Task<TimedResult<byte[]?>> Get(byte[] key)
    {
        Interlocked.Increment(ref _getCount);
        long start = Stopwatch.GetTimestamp();
        await SimulateDelay().ConfigureAwait(false);
        long latencyMicros = GetElapsedMicros(start);
        bool success = !ShouldSimulateError();

        Record("GET", key, null, success, success ? null : _errorMessage);
        if (!success) throw new InvalidOperationException(_errorMessage);
        _data.TryGetValue(System.Text.Encoding.UTF8.GetString(key), out var val);
        return TimedResult<byte[]?>.Of(val, latencyMicros);
    }

    public async Task<TimedResult<string?>> Ping()
    {
        Interlocked.Increment(ref _pingCount);
        long start = Stopwatch.GetTimestamp();
        await SimulateDelay().ConfigureAwait(false);
        long latencyMicros = GetElapsedMicros(start);
        bool success = !ShouldSimulateError();

        Record("PING", null, null, success, success ? null : _errorMessage);
        if (!success) throw new InvalidOperationException(_errorMessage);
        return TimedResult<string?>.Of("PONG", latencyMicros);
    }

    public Task<TimedResult<string?>> Ping(byte[] message) => Ping();

    public async Task<TimedResult<long>> Del(params byte[][] keys)
    {
        Interlocked.Increment(ref _delCount);
        long start = Stopwatch.GetTimestamp();
        await SimulateDelay().ConfigureAwait(false);
        long latencyMicros = GetElapsedMicros(start);
        bool success = !ShouldSimulateError();

        long count = 0;
        if (success)
            foreach (var key in keys)
                if (_data.TryRemove(System.Text.Encoding.UTF8.GetString(key), out _)) count++;

        foreach (var key in keys)
            Record("DEL", key, null, success, success ? null : _errorMessage);
        if (!success) throw new InvalidOperationException(_errorMessage);
        return TimedResult<long>.Of(count, latencyMicros);
    }

    public async Task<TimedResult<object?>> FlushDb()
    {
        long start = Stopwatch.GetTimestamp();
        await SimulateDelay().ConfigureAwait(false);
        long latencyMicros = GetElapsedMicros(start);
        _data.Clear();
        Record("FLUSHDB", null, null, true, null);
        return TimedResult<object?>.OfVoid(latencyMicros);
    }

    public void Dispose()
    {
        _connected = false;
        Record("CLOSE", null, null, true, null);
    }

    // === Static registry methods ===

    public static IReadOnlyList<RecordingBenchmarkClient> GetInstances() => Instances.ToList();

    public static void ClearInstances()
    {
        Instances.Clear();
        while (AggregateOps.TryDequeue(out _)) { }
    }

    public static IReadOnlyList<RecordedOperation> GetAggregateOperations() => AggregateOps.ToList();

    public static IReadOnlyList<RecordedOperation> GetAggregateOperations(string command) =>
        AggregateOps.Where(op => op.Command == command).ToList();

    public static IReadOnlyList<RecordedOperation> GetAggregateSetOperationsWithValues() =>
        AggregateOps.Where(op => op.Command == "SET" && op.Value != null).ToList();

    public static IReadOnlyList<string> GetAggregateUniqueKeys() =>
        AggregateOps.Where(op => op.Key != null)
            .Select(op => System.Text.Encoding.UTF8.GetString(op.Key!))
            .Distinct().ToList();

    // === Instance accessors ===

    public long SetCount => Interlocked.Read(ref _setCount);
    public long GetCount => Interlocked.Read(ref _getCount);
    public long PingCount => Interlocked.Read(ref _pingCount);
    public long DelCount => Interlocked.Read(ref _delCount);
    public IReadOnlyList<RecordedOperation> Operations => _operations.ToList();
    public ConcurrentDictionary<string, byte[]> StoredData => _data;

    // === Delay simulation ===

    private async Task SimulateDelay()
    {
        long delayMs = CalculateDelayMs();
        if (delayMs <= 0) return;
        await Task.Delay((int)delayMs).ConfigureAwait(false);
    }

    private long CalculateDelayMs()
    {
        if (_latencyDistribution == "log_normal")
        {
            double z = NextGaussian();
            double sampleMs = Math.Exp(_logNormalMu + _logNormalSigma * z);
            return Math.Max(_latencyMinMs, (long)sampleMs);
        }
        else
        {
            if (_operationDelayMicros <= 0) return 0;
            long actual = _operationDelayMicros;
            if (_delayVariationMicros > 0)
            {
                long variation = (long)(_random.NextDouble() * 2 * _delayVariationMicros) - _delayVariationMicros;
                actual = Math.Max(0, _operationDelayMicros + variation);
            }
            return actual / 1000;
        }
    }

    private double NextGaussian()
    {
        // Box-Muller transform
        double u1 = 1.0 - _random.NextDouble();
        double u2 = 1.0 - _random.NextDouble();
        return Math.Sqrt(-2.0 * Math.Log(u1)) * Math.Sin(2.0 * Math.PI * u2);
    }

    private bool ShouldSimulateError()
    {
        if (_warmupMode) return false;
        if (_errorRate <= 0.0) return false;
        if (_errorRate >= 1.0) return true;
        return _random.NextDouble() < _errorRate;
    }

    private void Record(string command, byte[]? key, byte[]? value, bool success, string? errorMessage)
    {
        var op = new RecordedOperation(command, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), key, value, success, errorMessage);
        _operations.Enqueue(op);
        AggregateOps.Enqueue(op);
    }

    private static long GetElapsedMicros(long startTimestamp)
    {
        long elapsed = Stopwatch.GetTimestamp() - startTimestamp;
        return elapsed * 1_000_000 / Stopwatch.Frequency;
    }

    // === Getters for test inspection ===
    public string LatencyDistribution => _latencyDistribution;
    public long LatencyMinMs => _latencyMinMs;
    public double LogNormalMu => _logNormalMu;
    public double LogNormalSigma => _logNormalSigma;
    public double ErrorRate => _errorRate;

    public record RecordedOperation(
        string Command, long Timestamp, byte[]? Key, byte[]? Value, bool Success, string? ErrorMessage);
}
