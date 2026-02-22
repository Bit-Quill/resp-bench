# frozen_string_literal: true

module RespBench
  module Config
    # Configuration for a benchmark workload.
    # Maps to the JSON schema in configs/schemas/workload-config.schema.json
    class WorkloadConfig
      attr_accessor :schema_version, :benchmark_profile, :phases

      def initialize(schema_version:, benchmark_profile:, phases:)
        @schema_version = schema_version
        @benchmark_profile = benchmark_profile
        @phases = phases
      end

      def name
        @benchmark_profile[:name]
      end

      def description
        @benchmark_profile[:description]
      end

      def to_h
        {
          schema_version: @schema_version,
          benchmark_profile: @benchmark_profile,
          phases: @phases.map(&:to_h)
        }
      end
    end
  end
end
