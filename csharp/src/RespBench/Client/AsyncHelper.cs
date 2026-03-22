/*
 * Copyright 2025 the original author or authors.
 */
using System.Diagnostics;

namespace RespBench.Client;

/// <summary>
/// Synchronous timing helper for all benchmark clients.
///
/// Provides a unified timing pattern for driver operations. Each operation is
/// executed inline on the calling thread (which is already a long-lived Task
/// owned by BenchmarkEngine), timed with Stopwatch, and wrapped in an
/// already-completed Task for interface compatibility.
/// </summary>
public static class AsyncHelper
{
    /// <summary>
    /// Execute a blocking operation synchronously with timing.
    /// Returns an already-completed task with the timed result.
    /// </summary>
    public static Task<TimedResult<T>> Timed<T>(Func<T> operation)
    {
        long start = Stopwatch.GetTimestamp();
        try
        {
            T result = operation();
            long latencyMicros = GetElapsedMicros(start);
            return Task.FromResult(TimedResult<T>.Of(result, latencyMicros));
        }
        catch (Exception e)
        {
            return Task.FromException<TimedResult<T>>(new InvalidOperationException(e.Message, e));
        }
    }

    /// <summary>
    /// Execute a void blocking operation synchronously with timing.
    /// Returns an already-completed task with the timed result.
    /// </summary>
    public static Task<TimedResult<object?>> TimedVoid(Action operation)
    {
        long start = Stopwatch.GetTimestamp();
        try
        {
            operation();
            long latencyMicros = GetElapsedMicros(start);
            return Task.FromResult(TimedResult<object?>.OfVoid(latencyMicros));
        }
        catch (Exception e)
        {
            return Task.FromException<TimedResult<object?>>(new InvalidOperationException(e.Message, e));
        }
    }

    /// <summary>
    /// Execute an async operation with timing.
    /// </summary>
    public static async Task<TimedResult<T>> TimedAsync<T>(Func<Task<T>> operation)
    {
        long start = Stopwatch.GetTimestamp();
        try
        {
            T result = await operation().ConfigureAwait(false);
            long latencyMicros = GetElapsedMicros(start);
            return TimedResult<T>.Of(result, latencyMicros);
        }
        catch (Exception e)
        {
            throw new InvalidOperationException(e.Message, e);
        }
    }

    /// <summary>
    /// Execute an async void operation with timing.
    /// </summary>
    public static async Task<TimedResult<object?>> TimedVoidAsync(Func<Task> operation)
    {
        long start = Stopwatch.GetTimestamp();
        try
        {
            await operation().ConfigureAwait(false);
            long latencyMicros = GetElapsedMicros(start);
            return TimedResult<object?>.OfVoid(latencyMicros);
        }
        catch (Exception e)
        {
            throw new InvalidOperationException(e.Message, e);
        }
    }

    private static long GetElapsedMicros(long startTimestamp)
    {
        long elapsed = Stopwatch.GetTimestamp() - startTimestamp;
        return elapsed * 1_000_000 / Stopwatch.Frequency;
    }
}
