# frozen_string_literal: true

module RespBench
  module Command
    module Impl
      # SET command implementation.
      class SetCommand < Command
        def initialize(config)
          super
          # Pre-generate value data of the configured size
          @value = generate_value(@data_size_bytes)
        end

        def execute(client, key_generator)
          key = key_generator.next_key
          result = client.set(key, @value)
          CommandResult.new(
            command_name: @name,
            latency_micros: result.latency_micros,
            success: result.success?
          )
        end

        private

        def generate_value(size)
          # Generate a deterministic value of the specified size
          # Using a simple pattern for reproducibility
          pattern = "0123456789ABCDEF"
          (pattern * ((size / pattern.length) + 1))[0, size]
        end
      end
    end
  end
end
