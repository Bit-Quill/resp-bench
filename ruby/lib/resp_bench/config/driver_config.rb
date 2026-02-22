# frozen_string_literal: true

module RespBench
  module Config
    # Configuration for a benchmark driver (client library).
    # Maps to the JSON schema in configs/schemas/driver-config.schema.json
    class DriverConfig
      attr_accessor :schema_version, :description, :driver_id, :mode,
                    :tls, :auth, :specific_driver_config

      def initialize(
        schema_version: "1.0",
        description: nil,
        driver_id: nil,
        mode: "standalone",
        tls: nil,
        auth: nil,
        specific_driver_config: {}
      )
        @schema_version = schema_version
        @description = description
        @driver_id = driver_id
        @mode = mode
        @tls = tls
        @auth = auth
        @specific_driver_config = specific_driver_config || {}
      end

      # Get secondary driver ID for composite drivers (e.g., spring-data-*)
      def secondary_driver_id
        @specific_driver_config["secondary_driver_id"]
      end

      def standalone?
        @mode == "standalone"
      end

      def cluster?
        @mode == "cluster"
      end

      def sentinel?
        @mode == "sentinel"
      end

      def to_h
        {
          schema_version: @schema_version,
          description: @description,
          driver_id: @driver_id,
          mode: @mode,
          tls: @tls,
          auth: @auth,
          specific_driver_config: @specific_driver_config
        }
      end
    end
  end
end
