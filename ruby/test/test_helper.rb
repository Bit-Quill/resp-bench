# frozen_string_literal: true

$LOAD_PATH.unshift File.expand_path("../lib", __dir__)
require "resp_bench"
require "minitest/autorun"

# Test helper methods
module TestHelper
  # Create a keyspace config for testing
  def create_keyspace_config(
    keys_count: 100,
    key_size_bytes: 16,
    key_prefix: "test:",
    generation_alg: "sequential_int",
    seed: nil
  )
    RespBench::Config::KeyspaceConfig.new(
      keys_count: keys_count,
      key_size_bytes: key_size_bytes,
      key_prefix: key_prefix,
      generation_alg: generation_alg,
      seed: seed
    )
  end

  # Create a command config for testing
  def create_command_config(command: "set", weight: 1.0, data_size_bytes: 256)
    RespBench::Config::CommandConfig.new(
      command: command,
      weight: weight,
      data_size_bytes: data_size_bytes
    )
  end

  # Default server host
  def server_host
    ENV.fetch("VALKEY_HOST", "localhost")
  end

  # Default server port
  def server_port
    ENV.fetch("VALKEY_PORT", "6379").to_i
  end
end
