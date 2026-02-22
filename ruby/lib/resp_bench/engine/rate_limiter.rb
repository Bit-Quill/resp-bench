# frozen_string_literal: true

require "concurrent"

module RespBench
  module Engine
    # Leaky bucket rate limiter that enforces a constant rate without burst.
    #
    # This implementation ensures operations are evenly spaced at the specified rate.
    # For example, with rps_limit=20, operations will be spaced ~50ms apart.
    #
    # Unlike token bucket, there is no burst capacity - the rate is strictly enforced
    # from the first operation.
    #
    # Matches the Java reference implementation behavior.
    class RateLimiter
      # @return [Integer] the configured rate per second
      attr_reader :rate_per_second

      # Create a rate limiter with the specified rate.
      #
      # @param rate_per_second [Integer] operations per second (must be > 0)
      # @return [RateLimiter, nil] rate limiter instance, or nil if rate <= 0
      def self.create(rate_per_second)
        return nil if rate_per_second <= 0

        new(rate_per_second)
      end

      # Acquire permission for one operation, blocking until allowed.
      # Operations are evenly spaced at the configured rate.
      def acquire
        loop do
          now = monotonic_nanos
          next_allowed = @next_allowed_nanos.value

          if now >= next_allowed
            # Try to claim this slot and advance to next
            if @next_allowed_nanos.compare_and_set(next_allowed, next_allowed + @interval_nanos)
              return # Successfully acquired
            end
            # Another thread claimed it, retry
          else
            # Need to wait until next allowed time
            wait_nanos = next_allowed - now
            sleep_duration = wait_nanos / 1_000_000_000.0
            sleep(sleep_duration) if sleep_duration > 0
          end
        end
      end

      # Try to acquire permission for one operation without blocking.
      #
      # @return [Boolean] true if acquired, false if rate limit would be exceeded
      def try_acquire
        now = monotonic_nanos
        next_allowed = @next_allowed_nanos.value

        return false if now < next_allowed

        @next_allowed_nanos.compare_and_set(next_allowed, next_allowed + @interval_nanos)
      end

      private

      def initialize(rate_per_second)
        @rate_per_second = rate_per_second
        # Calculate interval between operations in nanoseconds
        # For 20 ops/sec: 1_000_000_000 / 20 = 50_000_000 ns = 50ms
        @interval_nanos = 1_000_000_000 / rate_per_second
        # First operation is allowed immediately
        @next_allowed_nanos = Concurrent::AtomicFixnum.new(monotonic_nanos)
      end

      # Get current monotonic time in nanoseconds
      def monotonic_nanos
        Process.clock_gettime(Process::CLOCK_MONOTONIC, :nanosecond)
      end
    end
  end
end
