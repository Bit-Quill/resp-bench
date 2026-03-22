/*
 * Copyright 2025 the original author or authors.
 */
using System.Collections.Concurrent;
using System.Diagnostics;
using HdrHistogram;
using RespBench.Command;
using System.Threading;

namespace RespBench.Metrics;

/// <summary>
/// Collects metrics for benchmark operations using HdrHistogram.
/// </summary>
public class MetricsCollector
{
    private readonly ConcurrentDictionary<string, CommandMetrics> _commandMetrics = new();
    private long _totalRequests;
    private long _totalErrors;
    private long _startTime;
    private long _endTime;
    private readonly Stopwatch _stopwatch = new();

    public void Start()
    {
        _startTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        _stopwatch.Restart();
    }

    public void Stop()
    {
        _stopwatch.Stop();
        _endTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    }

    public void Record(CommandResult result)
    {
        Interlocked.Increment(ref _totalRequests);
        if (!result.Success) Interlocked.Increment(ref _totalErrors);

        _commandMetrics.GetOrAdd(result.CommandName, name => new CommandMetrics(name))
            .Record(result);
    }

    public long TotalRequests => Interlocked.Read(ref _totalRequests);
    public long TotalErrors => Interlocked.Read(ref _totalErrors);
    public long StartTime => _startTime;
    public long EndTime => _endTime;
    public long DurationMillis
    {
        get
        {
            // Use Stopwatch for precise sub-millisecond timing, ceiling to ensure > 0 when work was done
            long swMs = (long)Math.Ceiling(_stopwatch.Elapsed.TotalMilliseconds);
            long wallMs = _endTime - _startTime;
            return Math.Max(swMs, wallMs);
        }
    }

    public CommandMetrics? GetMetrics(string commandName) =>
        _commandMetrics.TryGetValue(commandName, out var m) ? m : null;

    public IReadOnlyDictionary<string, CommandMetrics> AllMetrics => _commandMetrics;

    public void Reset()
    {
        _commandMetrics.Clear();
        Interlocked.Exchange(ref _totalRequests, 0);
        Interlocked.Exchange(ref _totalErrors, 0);
        _startTime = 0;
        _endTime = 0;
        _stopwatch.Reset();
    }
}

/// <summary>
/// Metrics for a specific command. Uses LongConcurrentHistogram for thread-safe access.
/// </summary>
public class CommandMetrics
{
    private readonly string _commandName;
    private readonly LongConcurrentHistogram _histogram;
    private long _requests;
    private long _errors;

    public CommandMetrics(string commandName)
    {
        _commandName = commandName;
        // Track latencies up to 10 minutes (600,000,000 µs) with 3 significant digits
        _histogram = new LongConcurrentHistogram(1, 600_000_000L, 3);
    }

    public void Record(CommandResult result)
    {
        Interlocked.Increment(ref _requests);
        if (result.Success)
        {
            _histogram.RecordValue(Math.Max(1, Math.Min(result.LatencyMicros, 600_000_000L)));
        }
        else
        {
            Interlocked.Increment(ref _errors);
        }
    }

    public string CommandName => _commandName;
    public long Requests => Interlocked.Read(ref _requests);
    public long Errors => Interlocked.Read(ref _errors);
    public HistogramBase Histogram => _histogram;

    public long Min => _histogram.TotalCount > 0 ? _histogram.GetValueAtPercentile(0) : 0;
    public long Max => _histogram.TotalCount > 0 ? _histogram.GetMaxValue() : 0;
    public double Mean => _histogram.TotalCount > 0 ? _histogram.GetMean() : 0;
    public long P50 => _histogram.TotalCount > 0 ? _histogram.GetValueAtPercentile(50) : 0;
    public long P90 => _histogram.TotalCount > 0 ? _histogram.GetValueAtPercentile(90) : 0;
    public long P95 => _histogram.TotalCount > 0 ? _histogram.GetValueAtPercentile(95) : 0;
    public long P99 => _histogram.TotalCount > 0 ? _histogram.GetValueAtPercentile(99) : 0;
    public long P999 => _histogram.TotalCount > 0 ? _histogram.GetValueAtPercentile(99.9) : 0;
}
