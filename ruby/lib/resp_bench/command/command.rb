# frozen_string_literal: true

module RespBench
  module Command
    # Result of a command execution, used for metrics collection.
    CommandResult = Struct.new(:command_name, :latency_micros, :success, keyword_init: true) do
      def success?
        success
      end
    end

    # Abstract base class for benchmark commands.
    class Command
      attr_reader :weight, :name

      def initialize(config)
        @weight = config.weight
        @name = config.command.upcase
        @data_size_bytes = config.data_size_bytes
      end

      # Execute the command and return a result for metrics
      # @param client [RespBench::Client::BenchmarkClient] the client
      # @param key_generator [RespBench::Engine::KeyGenerator] key generator
      # @return [CommandResult] the result with latency and success status
      def execute(client, key_generator)
        raise NotImplementedError, "#{self.class} must implement #execute"
      end
    end
  end
end
