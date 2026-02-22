# frozen_string_literal: true

require_relative "resp_bench/version"

# Configuration
require_relative "resp_bench/config/driver_config"
require_relative "resp_bench/config/workload_config"
require_relative "resp_bench/config/phase_config"
require_relative "resp_bench/config/keyspace_config"
require_relative "resp_bench/config/command_config"
require_relative "resp_bench/config/completion_config"
require_relative "resp_bench/config/config_loader"

# Engine
require_relative "resp_bench/engine/java_random"
require_relative "resp_bench/engine/key_generator"
require_relative "resp_bench/engine/rate_limiter"
require_relative "resp_bench/engine/command_selector"
require_relative "resp_bench/engine/benchmark_engine"

# Client
require_relative "resp_bench/client/timed_result"
require_relative "resp_bench/client/benchmark_client"
require_relative "resp_bench/client/benchmark_client_factory"

# Commands
require_relative "resp_bench/command/command"
require_relative "resp_bench/command/command_factory"

# Metrics
require_relative "resp_bench/metrics/metrics_collector"
require_relative "resp_bench/metrics/ndjson_writer"

module RespBench
  class Error < StandardError; end
end
