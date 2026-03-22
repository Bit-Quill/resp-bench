/*
 * Copyright 2025 the original author or authors.
 */
namespace RespBench.Client;

/// <summary>
/// Wraps a result with its execution latency in microseconds.
/// </summary>
public class TimedResult<T>
{
    public T? Value { get; }
    public long LatencyMicros { get; }

    public TimedResult(T? value, long latencyMicros)
    {
        Value = value;
        LatencyMicros = latencyMicros;
    }

    /// <summary>Create a TimedResult for a void operation.</summary>
    public static TimedResult<object?> OfVoid(long latencyMicros) => new(null, latencyMicros);

    /// <summary>Create a TimedResult with a value.</summary>
    public static TimedResult<T> Of(T? value, long latencyMicros) => new(value, latencyMicros);
}
