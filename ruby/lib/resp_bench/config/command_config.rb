# frozen_string_literal: true

module RespBench
  module Config
    # Configuration for a command in a benchmark phase.
    class CommandConfig
      DEFAULT_DATA_SIZE_BYTES = 256

      attr_accessor :command, :weight, :data_size_bytes

      def initialize(
        command:,
        weight:,
        data_size_bytes: DEFAULT_DATA_SIZE_BYTES
      )
        @command = command.downcase
        @weight = weight.to_f
        @data_size_bytes = data_size_bytes || DEFAULT_DATA_SIZE_BYTES
      end

      def to_h
        {
          command: @command,
          weight: @weight,
          data_size_bytes: @data_size_bytes
        }
      end
    end
  end
end
