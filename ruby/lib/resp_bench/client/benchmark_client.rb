# frozen_string_literal: true

module RespBench
  module Client
    # Abstract interface for benchmark clients.
    # All driver implementations must implement this interface.
    class BenchmarkClient
      # Connect to the server
      # @param host [String] server hostname
      # @param port [Integer] server port
      # @param config [RespBench::Config::DriverConfig] driver configuration
      def connect(host, port, config)
        raise NotImplementedError, "#{self.class} must implement #connect"
      end

      # Check if connected
      # @return [Boolean] true if connected
      def connected?
        raise NotImplementedError, "#{self.class} must implement #connected?"
      end

      # Execute PING command
      # @return [TimedResult<String>] result with "PONG" value
      def ping
        raise NotImplementedError, "#{self.class} must implement #ping"
      end

      # Execute GET command
      # @param key [String] the key to get
      # @return [TimedResult<String, nil>] result with value or nil if not found
      def get(key)
        raise NotImplementedError, "#{self.class} must implement #get"
      end

      # Execute SET command
      # @param key [String] the key to set
      # @param value [String] the value to set
      # @return [TimedResult<String>] result with "OK"
      def set(key, value)
        raise NotImplementedError, "#{self.class} must implement #set"
      end

      # Execute DEL command
      # @param key [String] the key to delete
      # @return [TimedResult<Integer>] result with number of keys deleted
      def del(key)
        raise NotImplementedError, "#{self.class} must implement #del"
      end

      # Close the connection
      def close
        raise NotImplementedError, "#{self.class} must implement #close"
      end

      # Get the driver version
      # @return [String] driver library version
      def driver_version
        raise NotImplementedError, "#{self.class} must implement #driver_version"
      end

      # Get secondary driver version (for composite drivers)
      # @return [String, nil] secondary driver version or nil
      def secondary_driver_version
        nil
      end

      protected

      # Measure the execution time of a block in microseconds
      # @yield the operation to measure
      # @return [TimedResult] result with latency
      def measure
        start = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond)
        begin
          result = yield
          latency = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond) - start
          TimedResult.new(value: result, latency_micros: latency)
        rescue StandardError => e
          latency = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond) - start
          TimedResult.new(value: nil, latency_micros: latency, error: e)
        end
      end
    end
  end
end
