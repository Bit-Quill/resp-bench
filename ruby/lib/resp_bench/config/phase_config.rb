# frozen_string_literal: true

module RespBench
  module Config
    # Configuration for a benchmark phase.
    class PhaseConfig
      DEFAULT_PIPELINE_DEPTH = 1
      DEFAULT_WARMUP_REQUESTS = 1

      attr_accessor :id, :description, :connections, :cps_limit, :rps_limit,
                    :pipeline_depth, :warmup_requests, :completion, :keyspace, :commands

      def initialize(
        id:,
        connections:,
        completion:,
        keyspace:,
        commands:,
        description: nil,
        cps_limit: -1,
        rps_limit: -1,
        pipeline_depth: DEFAULT_PIPELINE_DEPTH,
        warmup_requests: DEFAULT_WARMUP_REQUESTS
      )
        @id = id
        @description = description
        @connections = connections
        @cps_limit = cps_limit || -1
        @rps_limit = rps_limit || -1
        @pipeline_depth = pipeline_depth || DEFAULT_PIPELINE_DEPTH
        @warmup_requests = warmup_requests || DEFAULT_WARMUP_REQUESTS
        @completion = completion
        @keyspace = keyspace
        @commands = commands
      end

      def cps_limit?
        @cps_limit.positive?
      end

      def rps_limit?
        @rps_limit.positive?
      end

      def effective_pipeline_depth
        @pipeline_depth.positive? ? @pipeline_depth : DEFAULT_PIPELINE_DEPTH
      end

      def to_h
        {
          id: @id,
          description: @description,
          connections: @connections,
          cps_limit: @cps_limit,
          rps_limit: @rps_limit,
          pipeline_depth: @pipeline_depth,
          warmup_requests: @warmup_requests,
          completion: @completion.to_h,
          keyspace: @keyspace.to_h,
          commands: @commands.map(&:to_h)
        }
      end
    end
  end
end
