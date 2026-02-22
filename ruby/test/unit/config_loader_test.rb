# frozen_string_literal: true

require_relative "../test_helper"

class ConfigLoaderTest < Minitest::Test
  def test_parse_driver_config_string
    json = <<~JSON
      {"driver_id": "redis-rb", "mode": "standalone"}
    JSON

    config = RespBench::Config::ConfigLoader.parse_driver_config_string(json)

    assert_equal "redis-rb", config.driver_id
    assert_equal "standalone", config.mode
  end

  def test_parse_driver_config_with_all_fields
    json = {
      schema_version: "1.0",
      description: "Test driver",
      driver_id: "redis-rb",
      mode: "cluster",
      tls: { enabled: true },
      auth: { password: "secret" },
      specific_driver_config: { timeout: 5000 }
    }

    config = RespBench::Config::ConfigLoader.parse_driver_config(json)

    assert_equal "1.0", config.schema_version
    assert_equal "Test driver", config.description
    assert_equal "redis-rb", config.driver_id
    assert_equal "cluster", config.mode
    assert config.tls
    assert config.auth
    assert_equal 5000, config.specific_driver_config[:timeout]
  end

  def test_parse_workload_config
    json = {
      schema_version: "1.0",
      benchmark_profile: { name: "Test Benchmark", description: "A test" },
      phases: [
        {
          id: "WARMUP",
          connections: 10,
          completion: { type: "requests", requests: 1000 },
          keyspace: {
            keys_count: 1000,
            key_prefix: "bench:",
            generation_alg: "sequential_int"
          },
          commands: [
            { command: "set", weight: 1.0, data_size_bytes: 256 }
          ]
        }
      ]
    }

    config = RespBench::Config::ConfigLoader.parse_workload_config(json)

    assert_equal "Test Benchmark", config.name
    assert_equal 1, config.phases.size

    phase = config.phases.first
    assert_equal "WARMUP", phase.id
    assert_equal 10, phase.connections
    assert phase.completion.request_based?
    assert_equal 1000, phase.completion.total_requests
    assert_equal 1000, phase.keyspace.keys_count
    assert phase.keyspace.sequential_int?
    assert_equal 1, phase.commands.size
  end

  def test_parse_duration_completion
    json = {
      schema_version: "1.0",
      benchmark_profile: { name: "Test" },
      phases: [
        {
          id: "STEADY",
          connections: 1,
          completion: { type: "duration", seconds: 60 },
          keyspace: { keys_count: 100, key_prefix: "test:", generation_alg: "sequential_int" },
          commands: [{ command: "get", weight: 1.0 }]
        }
      ]
    }

    config = RespBench::Config::ConfigLoader.parse_workload_config(json)
    phase = config.phases.first

    assert phase.completion.duration_based?
    assert_equal 60, phase.completion.duration_seconds
  end

  def test_parse_phase_with_limits
    json = {
      schema_version: "1.0",
      benchmark_profile: { name: "Test" },
      phases: [
        {
          id: "LIMITED",
          connections: 10,
          cps_limit: 5,
          rps_limit: 1000,
          pipeline_depth: 4,
          warmup_requests: 2,
          completion: { type: "requests", requests: 1000 },
          keyspace: { keys_count: 100, key_prefix: "test:", generation_alg: "sequential_int" },
          commands: [{ command: "get", weight: 1.0 }]
        }
      ]
    }

    config = RespBench::Config::ConfigLoader.parse_workload_config(json)
    phase = config.phases.first

    assert phase.cps_limit?
    assert_equal 5, phase.cps_limit
    assert phase.rps_limit?
    assert_equal 1000, phase.rps_limit
    assert_equal 4, phase.pipeline_depth
    assert_equal 2, phase.warmup_requests
  end

  def test_parse_uniform_rand_keyspace
    json = {
      schema_version: "1.0",
      benchmark_profile: { name: "Test" },
      phases: [
        {
          id: "RANDOM",
          connections: 1,
          completion: { type: "requests", requests: 100 },
          keyspace: {
            keys_count: 10_000,
            key_prefix: "rand:",
            generation_alg: "uniform_rand",
            seed: 12345
          },
          commands: [{ command: "get", weight: 1.0 }]
        }
      ]
    }

    config = RespBench::Config::ConfigLoader.parse_workload_config(json)
    phase = config.phases.first

    assert phase.keyspace.uniform_rand?
    assert_equal 12345, phase.keyspace.seed
  end

  def test_load_driver_config_from_file
    # Create a temporary config file
    require "tempfile"
    file = Tempfile.new(["driver", ".json"])
    file.write('{"driver_id": "redis-rb", "mode": "standalone"}')
    file.close

    config = RespBench::Config::ConfigLoader.load_driver_config(file.path)

    assert_equal "redis-rb", config.driver_id
  ensure
    file&.unlink
  end

  # Parity with Java's ConfigLoaderTest.shouldDetectClusterMode
  def test_detect_cluster_mode
    config = RespBench::Config::ConfigLoader.parse_driver_config_string(
      '{"driver_id": "redis-rb", "mode": "cluster"}'
    )

    assert config.cluster?
    refute config.standalone?
  end

  # Parity with Java's ConfigLoaderTest.shouldDetectTlsEnabled
  def test_detect_tls_enabled
    config = RespBench::Config::ConfigLoader.parse_driver_config_string(
      '{"driver_id": "redis-rb", "mode": "standalone", "tls": {}}'
    )

    refute_nil config.tls, "TLS config should be present"
  end

  # Parity with Java's ConfigLoaderTest.shouldThrowOnMissingDriverId
  # Note: Ruby's ConfigLoader doesn't validate missing fields (creates a config with nil driver_id).
  # This test documents that behavior. If validation is added later, update accordingly.
  def test_missing_driver_id_results_in_nil
    config = RespBench::Config::ConfigLoader.parse_driver_config_string('{}')

    assert_nil config.driver_id
  end
end
