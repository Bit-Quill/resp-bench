# frozen_string_literal: true

require_relative "../test_helper"

class RateLimiterTest < Minitest::Test
  def test_create_returns_nil_for_negative_rate
    limiter = RespBench::Engine::RateLimiter.create(-1)
    assert_nil limiter
  end

  def test_create_returns_nil_for_zero_rate
    limiter = RespBench::Engine::RateLimiter.create(0)
    assert_nil limiter
  end

  def test_create_returns_instance_for_positive_rate
    limiter = RespBench::Engine::RateLimiter.create(100)
    refute_nil limiter
  end

  def test_rate_per_second_returns_configured_rate
    limiter = RespBench::Engine::RateLimiter.create(42)
    assert_equal 42, limiter.rate_per_second
  end

  def test_try_acquire_succeeds_on_first_call
    limiter = RespBench::Engine::RateLimiter.create(20)
    assert limiter.try_acquire
  end

  def test_try_acquire_fails_immediately_after_first_call
    # With leaky bucket, second call should fail if made immediately
    limiter = RespBench::Engine::RateLimiter.create(20) # 50ms interval

    assert limiter.try_acquire  # First succeeds
    refute limiter.try_acquire  # Second fails (no time elapsed)
  end

  def test_try_acquire_succeeds_after_interval
    rate = 20 # 50ms interval
    limiter = RespBench::Engine::RateLimiter.create(rate)

    assert limiter.try_acquire  # First succeeds

    # Wait for interval
    sleep(0.055) # slightly more than 50ms

    assert limiter.try_acquire  # Should succeed after interval
  end

  def test_acquire_enforces_constant_rate
    rate = 20 # 20 ops/sec = 50ms interval
    limiter = RespBench::Engine::RateLimiter.create(rate)

    start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC)

    # Acquire 5 operations
    5.times { limiter.acquire }

    duration_ms = (Process.clock_gettime(Process::CLOCK_MONOTONIC) - start_time) * 1000

    # 5 ops at 20/sec with first immediate: 4 intervals of 50ms = 200ms
    # Allow some tolerance for timing variability
    assert duration_ms >= 180, "Expected duration >= 180ms, got #{duration_ms}ms"
  end

  def test_acquire_maintains_rate_over_time
    rate = 20 # 20 ops/sec
    limiter = RespBench::Engine::RateLimiter.create(rate)

    start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    count = 0

    # Run for 1 second
    while (Process.clock_gettime(Process::CLOCK_MONOTONIC) - start_time) < 1.0
      limiter.acquire
      count += 1
    end

    # Should have executed approximately 20 operations
    # With 5% tolerance: 19-21 (matching Java's isBetween(19, 21))
    assert count >= 19 && count <= 21, "Expected 19-21 ops, got #{count}"
  end

  def test_low_rate_creates_significant_delay
    rate = 5 # 5 ops/sec = 200ms interval
    limiter = RespBench::Engine::RateLimiter.create(rate)

    start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC)

    # 3 operations: first immediate, then 2 waits of 200ms
    3.times { limiter.acquire }

    duration_ms = (Process.clock_gettime(Process::CLOCK_MONOTONIC) - start_time) * 1000

    # Should take ~400ms (2 intervals of 200ms)
    assert duration_ms >= 380, "Expected duration >= 380ms, got #{duration_ms}ms"
  end
end
