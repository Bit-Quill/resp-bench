# frozen_string_literal: true

module RespBench
  module Command
    module Impl
      # GET command implementation.
      class GetCommand < Command
        def execute(client, key_generator)
          key = key_generator.next_key
          result = client.get(key)
          CommandResult.new(
            command_name: @name,
            latency_micros: result.latency_micros,
            success: result.success?
          )
        end
      end
    end
  end
end
