# frozen_string_literal: true

module RespBench
  module Command
    module Impl
      # PING command implementation.
      class PingCommand < Command
        def execute(client, _key_generator)
          result = client.ping
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
