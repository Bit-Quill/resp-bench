# frozen_string_literal: true

require_relative "impl/ping_command"
require_relative "impl/get_command"
require_relative "impl/set_command"

module RespBench
  module Command
    # Factory for creating command instances.
    class CommandFactory
      COMMAND_CLASSES = {
        "ping" => Impl::PingCommand,
        "get" => Impl::GetCommand,
        "set" => Impl::SetCommand
        # Future: "hget", "hset", "lpush", "lpop", "sadd", "smembers"
      }.freeze

      class << self
        # Create a single command instance
        # @param config [RespBench::Config::CommandConfig] command configuration
        # @return [Command] command instance
        def create(config)
          command_class = COMMAND_CLASSES[config.command]
          raise ArgumentError, "Unknown command: #{config.command}. Supported: #{COMMAND_CLASSES.keys.join(', ')}" unless command_class

          command_class.new(config)
        end

        # Create all commands from a list of configs
        # @param configs [Array<RespBench::Config::CommandConfig>] command configurations
        # @return [Array<Command>] command instances
        def create_all(configs)
          configs.map { |c| create(c) }
        end

        # List supported commands
        # @return [Array<String>] list of supported command names
        def supported_commands
          COMMAND_CLASSES.keys
        end
      end
    end
  end
end
