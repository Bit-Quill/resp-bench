# frozen_string_literal: true

module RespBench
  module Config
    # Configuration for phase completion criteria.
    class CompletionConfig
      attr_accessor :type, :seconds, :requests

      def initialize(type:, seconds: nil, requests: nil)
        @type = type
        @seconds = seconds
        @requests = requests
      end

      def duration_based?
        @type == "duration"
      end

      def request_based?
        @type == "requests"
      end

      def duration_seconds
        @seconds || 0
      end

      def total_requests
        @requests || 0
      end

      def to_h
        hash = { type: @type }
        hash[:seconds] = @seconds if duration_based?
        hash[:requests] = @requests if request_based?
        hash
      end
    end
  end
end
