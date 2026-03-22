/*
 * Copyright 2025 the original author or authors.
 */
namespace RespBench.Engine;

/// <summary>
/// Leaky bucket rate limiter that enforces a constant rate without burst.
/// Operations are evenly spaced at the specified rate.
/// </summary>
public class RateLimiter
{
    private readonly int _ratePerSecond;
    private readonly long _intervalTicks;
    private long _nextAllowedTicks;

    private RateLimiter(int ratePerSecond)
    {
        _ratePerSecond = ratePerSecond;
        // Calculate interval between operations in Stopwatch ticks
        _intervalTicks = System.Diagnostics.Stopwatch.Frequency / ratePerSecond;
        // First operation is allowed immediately
        _nextAllowedTicks = System.Diagnostics.Stopwatch.GetTimestamp();
    }

    /// <summary>
    /// Create a rate limiter with the specified rate.
    /// Returns null if rate &lt;= 0.
    /// </summary>
    public static RateLimiter? Create(int ratePerSecond)
    {
        return ratePerSecond > 0 ? new RateLimiter(ratePerSecond) : null;
    }

    /// <summary>
    /// Acquire permission for one operation, blocking until allowed.
    /// Operations are evenly spaced at the configured rate.
    /// </summary>
    public async Task Acquire()
    {
        while (true)
        {
            long now = System.Diagnostics.Stopwatch.GetTimestamp();
            long next = Interlocked.Read(ref _nextAllowedTicks);

            if (now >= next)
            {
                // Try to claim this slot and advance to next
                if (Interlocked.CompareExchange(ref _nextAllowedTicks, next + _intervalTicks, next) == next)
                {
                    return; // Successfully acquired
                }
                // Another thread claimed it, retry
            }
            else
            {
                // Need to wait until next allowed time
                long waitTicks = next - now;
                long waitMs = waitTicks * 1000 / System.Diagnostics.Stopwatch.Frequency;

                if (waitMs > 0)
                {
                    await Task.Delay((int)waitMs).ConfigureAwait(false);
                }
                else
                {
                    // Sub-millisecond wait — spin
                    Thread.SpinWait(100);
                }
            }
        }
    }

    /// <summary>
    /// Try to acquire permission for one operation without blocking.
    /// </summary>
    public bool TryAcquire()
    {
        long now = System.Diagnostics.Stopwatch.GetTimestamp();
        long next = Interlocked.Read(ref _nextAllowedTicks);

        if (now >= next)
        {
            return Interlocked.CompareExchange(ref _nextAllowedTicks, next + _intervalTicks, next) == next;
        }
        return false;
    }

    /// <summary>Get the configured rate.</summary>
    public int RatePerSecond => _ratePerSecond;
}
