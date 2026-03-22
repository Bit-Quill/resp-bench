using FluentAssertions;
using RespBench.Engine;
using Xunit;

namespace RespBench.Tests.Unit;

public class RateLimiterTest
{
    [Fact]
    public void CreateReturnsNullForNegativeRate()
    {
        RateLimiter.Create(-1).Should().BeNull();
    }

    [Fact]
    public void CreateReturnsNullForZeroRate()
    {
        RateLimiter.Create(0).Should().BeNull();
    }

    [Fact]
    public void CreateReturnsInstanceForPositiveRate()
    {
        RateLimiter.Create(100).Should().NotBeNull();
    }

    [Fact]
    public void GetRatePerSecondReturnsConfiguredRate()
    {
        var limiter = RateLimiter.Create(42)!;
        limiter.RatePerSecond.Should().Be(42);
    }

    [Fact]
    public void TryAcquireSucceedsOnFirstCall()
    {
        var limiter = RateLimiter.Create(20)!;
        limiter.TryAcquire().Should().BeTrue();
    }

    [Fact]
    public void TryAcquireFailsImmediatelyAfterFirstCall()
    {
        var limiter = RateLimiter.Create(20)!;
        limiter.TryAcquire().Should().BeTrue();
        limiter.TryAcquire().Should().BeFalse();
    }

    [Fact]
    public async Task TryAcquireSucceedsAfterInterval()
    {
        var limiter = RateLimiter.Create(20)!; // 50ms interval
        limiter.TryAcquire().Should().BeTrue();
        await Task.Delay(55);
        limiter.TryAcquire().Should().BeTrue();
    }

    [Fact]
    public async Task AcquireEnforcesConstantRate()
    {
        var limiter = RateLimiter.Create(20)!;
        long startTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        for (int i = 0; i < 5; i++)
            await limiter.Acquire();

        long durationMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - startTime;
        durationMs.Should().BeGreaterOrEqualTo(180);
    }

    [Fact]
    public async Task AcquireMaintainsRateOverTime()
    {
        var limiter = RateLimiter.Create(20)!;
        long startTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        int count = 0;

        while (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - startTime < 1000)
        {
            await limiter.Acquire();
            count++;
        }

        count.Should().BeInRange(19, 22);
    }
}
