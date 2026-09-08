import time

from resp_bench.engine.rate_limiter import RateLimiter


def test_unlimited_when_rate_non_positive():
    assert RateLimiter.create(0) is None
    assert RateLimiter.create(-1) is None


async def test_enforces_rate_without_exceeding():
    rate = 500  # ops/sec -> 2ms interval
    n = 50
    limiter = RateLimiter.create(rate)
    start = time.monotonic()
    for _ in range(n):
        await limiter.acquire()
    elapsed = time.monotonic() - start

    # Leaky bucket: first acquire is immediate, so ~ (n-1) intervals expected.
    expected = (n - 1) / rate
    # The limiter must not let us exceed the target rate (allow 10% slack for
    # sleep granularity); the upper bound is loose to avoid CI flakiness.
    assert elapsed >= expected * 0.9
    assert elapsed <= expected * 3.0
