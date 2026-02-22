# frozen_string_literal: true

module RespBench
  module Client
    # Result of a timed operation, including latency and optional error.
    class TimedResult
      # @return [Object, nil] the result value (nil if error)
      attr_reader :value

      # @return [Integer] latency in microseconds
      attr_reader :latency_micros

      # @return [Exception, nil] error if operation failed
      attr_reader :error

      def initialize(value:, latency_micros:, error: nil)
        @value = value
        @latency_micros = latency_micros
        @error = error
      end

      # @return [Boolean] true if operation succeeded
      def success?
        @error.nil?
      end

      # @return [Boolean] true if operation failed
      def error?
        !@error.nil?
      end
    end
  end
end
