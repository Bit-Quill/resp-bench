# frozen_string_literal: true

require_relative "impl/redis_rb_client"
require_relative "impl/recording_client"

module RespBench
  module Client
    # Factory for creating benchmark client instances.
    class BenchmarkClientFactory
      DRIVERS = {
        "redis-rb" => Impl::RedisRbClient,
        "recording" => Impl::RecordingClient
        # Future: "valkey-glide" => Impl::ValkeyGlideClient
      }.freeze

      class << self
        # Create a client instance for the specified driver
        # @param driver_id [String] the driver ID (e.g., "redis-rb")
        # @return [BenchmarkClient] client instance
        def create(driver_id)
          driver_class = DRIVERS[driver_id]
          raise ArgumentError, "Unknown driver: #{driver_id}. Supported: #{DRIVERS.keys.join(', ')}" unless driver_class

          driver_class.new
        end

        # Create a client and connect to server
        # @param host [String] server hostname
        # @param port [Integer] server port
        # @param config [RespBench::Config::DriverConfig] driver configuration
        # @return [BenchmarkClient] connected client instance
        def create_and_connect(host, port, config)
          client = create(config.driver_id)
          client.connect(host, port, config)
          client
        end

        # List supported driver IDs
        # @return [Array<String>] list of supported driver IDs
        def supported_drivers
          DRIVERS.keys
        end
      end
    end
  end
end
