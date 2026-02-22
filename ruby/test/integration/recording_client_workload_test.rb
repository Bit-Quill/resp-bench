# frozen_string_literal: true

require_relative "../test_helper"
require "tempfile"
require "json"

# Black-box integration tests that validate workload configuration settings using RecordingClient.
#
# These tests follow the black-box testing approach:
# 1. Define driver and workload JSON strings with required configuration
# 2. Parse configs using ConfigLoader (same as Main does)
# 3. Run the BenchmarkEngine directly with recording client
# 4. Validate using static aggregate operations from RecordingClient
#
# The RecordingClient records all operations to a static aggregate collection
# that survives instance close, allowing tests to validate key prefixes, key distribution,
# data sizes, and key generation algorithms after the benchmark completes.
#
# Tests validate:
# - Key prefix is correctly applied to all generated keys
# - Key numbers are within the configured keys_count range
# - Sequential key generation produces keys in order and wraps correctly
# - Uniform random key generation produces reasonable distribution
# - Data size bytes produces values of correct size
class RecordingClientWorkloadTest < Minitest::Test
  include TestHelper

  def setup
    RespBench::Client::Impl::RecordingClient.clear_instances
    @host = "localhost"
    @port = 6379
  end

  def teardown
    RespBench::Client::Impl::RecordingClient.clear_instances
  end

  # === Key Prefix Tests ===

  # Test: Key prefix is applied to all keys
  def test_key_prefix_is_applied_to_all_keys
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "KeyPrefixTest"},
        "phases": [{
          "id": "KEY_PREFIX",
          "description": "Test key prefix is applied",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 64}],
          "keyspace": {
            "key_prefix": "myprefix:",
            "keys_count": 100,
            "key_size_bytes": 20,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 100}
        }]
      }
    JSON

    with_temp_metrics_file("key-prefix") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)

      # Validate via aggregate operations
      set_ops = RespBench::Client::Impl::RecordingClient.aggregate_operations_by_command("SET")
      assert_equal 100, set_ops.size, "Should have 100 SET operations"

      set_ops.each do |op|
        key = op.key.to_s
        assert key.start_with?("myprefix:"), "Key '#{key}' should start with 'myprefix:'"
      end
    end
  end

  # Test: Empty key prefix produces keys without prefix
  def test_empty_key_prefix_produces_keys_without_prefix
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "EmptyPrefixTest"},
        "phases": [{
          "id": "EMPTY_PREFIX",
          "description": "Test empty key prefix",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "",
            "keys_count": 50,
            "key_size_bytes": 10,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 50}
        }]
      }
    JSON

    with_temp_metrics_file("empty-prefix") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)

      # Validate via aggregate operations
      set_ops = RespBench::Client::Impl::RecordingClient.aggregate_operations_by_command("SET")
      assert_equal 50, set_ops.size, "Should have 50 SET operations"

      # Keys should be numeric only (padded)
      set_ops.each do |op|
        key = op.key.to_s
        assert_match(/^\d+$/, key, "Key '#{key}' should be numeric only")
      end
    end
  end

  # Test: Key numbers are within keys_count range
  def test_key_numbers_are_within_keys_count_range
    keys_count = 100

    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "KeyRangeTest"},
        "phases": [{
          "id": "KEY_RANGE",
          "description": "Test key numbers are within range",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "test:",
            "keys_count": #{keys_count},
            "key_size_bytes": 16,
            "generation_alg": "uniform_rand",
            "seed": 42
          },
          "completion": {"type": "requests", "requests": 500}
        }]
      }
    JSON

    with_temp_metrics_file("key-range") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)

      # Validate via aggregate operations
      set_ops = RespBench::Client::Impl::RecordingClient.aggregate_operations_by_command("SET")
      assert_equal 500, set_ops.size, "Should have 500 SET operations"

      key_pattern = /test:(\d+)/

      set_ops.each do |op|
        key = op.key.to_s
        match = key.match(key_pattern)
        assert match, "Key '#{key}' should match pattern 'test:<number>'"

        key_index = match[1].to_i
        assert key_index >= 0 && key_index < keys_count,
               "Key index #{key_index} should be between 0 and #{keys_count - 1}"
      end
    end
  end

  # Test: Sequential int generates sequential keys
  def test_sequential_int_generates_sequential_keys
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "SequentialKeysTest"},
        "phases": [{
          "id": "SEQUENTIAL_KEYS",
          "description": "Test sequential key generation",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "seq:",
            "keys_count": 1000,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 10}
        }]
      }
    JSON

    with_temp_metrics_file("sequential-keys") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)

      # Validate via aggregate operations
      set_ops = RespBench::Client::Impl::RecordingClient.aggregate_operations_by_command("SET")
      assert_equal 10, set_ops.size, "Should have 10 SET operations"

      # Extract key indices and verify they are sequential
      key_indices = []
      key_pattern = /seq:(\d+)/

      set_ops.each do |op|
        key = op.key.to_s
        match = key.match(key_pattern)
        assert match, "Key '#{key}' should match pattern 'seq:<number>'"
        key_indices << match[1].to_i
      end

      # Verify sequential order: 0, 1, 2, 3, ...
      key_indices.each_with_index do |idx, i|
        assert_equal i, idx, "Key index at position #{i} should be #{i}, got #{idx}"
      end
    end
  end

  # Test: Sequential int wraps around at keys_count
  def test_sequential_int_wraps_around_at_keys_count
    keys_count = 5

    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "WrapAroundTest"},
        "phases": [{
          "id": "WRAP_AROUND",
          "description": "Test key wrap-around at keys_count",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "wrap:",
            "keys_count": #{keys_count},
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 12}
        }]
      }
    JSON

    with_temp_metrics_file("wrap-around") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)

      # Validate via aggregate operations
      set_ops = RespBench::Client::Impl::RecordingClient.aggregate_operations_by_command("SET")
      assert_equal 12, set_ops.size, "Should have 12 SET operations"

      # Extract key indices
      key_indices = []
      key_pattern = /wrap:(\d+)/

      set_ops.each do |op|
        key = op.key.to_s
        match = key.match(key_pattern)
        assert match, "Key '#{key}' should match pattern 'wrap:<number>'"
        key_indices << match[1].to_i
      end

      # Expected: 0, 1, 2, 3, 4, 0, 1, 2, 3, 4, 0, 1
      expected = [0, 1, 2, 3, 4, 0, 1, 2, 3, 4, 0, 1]
      expected.each_with_index do |exp_idx, i|
        assert_equal exp_idx, key_indices[i],
                     "Key index at position #{i} should be #{exp_idx}, got #{key_indices[i]}"
      end
    end
  end

  # Test: Uniform random generates random distribution
  def test_uniform_rand_generates_random_distribution
    keys_count = 100

    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "RandomDistributionTest"},
        "phases": [{
          "id": "RANDOM_DIST",
          "description": "Test uniform random key distribution",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "rand:",
            "keys_count": #{keys_count},
            "key_size_bytes": 16,
            "generation_alg": "uniform_rand",
            "seed": 12345
          },
          "completion": {"type": "requests", "requests": 1000}
        }]
      }
    JSON

    with_temp_metrics_file("random-dist") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)

      # Validate via aggregate operations
      set_ops = RespBench::Client::Impl::RecordingClient.aggregate_operations_by_command("SET")
      assert_equal 1000, set_ops.size, "Should have 1000 SET operations"

      # Extract key indices and count distribution
      key_counts = Hash.new(0)
      key_pattern = /rand:(\d+)/

      set_ops.each do |op|
        key = op.key.to_s
        match = key.match(key_pattern)
        assert match, "Key '#{key}' should match pattern 'rand:<number>'"
        key_index = match[1].to_i
        key_counts[key_index] += 1
      end

      # With 1000 ops and 100 keys, each key should be hit ~10 times on average
      # Verify that keys are distributed (not all same key)
      assert_operator key_counts.size, :>, 50, "At least 50 unique keys should be hit"

      # Verify no single key has more than 5% of all operations (reasonable randomness)
      max_count = key_counts.values.max
      assert_operator max_count, :<, 50, "No key should have > 5% of ops"
    end
  end

  # Test: Data size bytes produces correct value size
  def test_data_size_bytes_produces_correct_value_size
    expected_data_size = 128

    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "DataSizeTest"},
        "phases": [{
          "id": "DATA_SIZE",
          "description": "Test data size bytes",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": #{expected_data_size}}],
          "keyspace": {
            "key_prefix": "data:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 20}
        }]
      }
    JSON

    with_temp_metrics_file("data-size") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)

      # Validate via aggregate operations
      set_ops = RespBench::Client::Impl::RecordingClient.aggregate_set_operations_with_values
      assert_equal 20, set_ops.size, "Should have 20 SET operations with values"

      # Verify all values have correct size
      set_ops.each do |op|
        assert_equal expected_data_size, op.value.length,
                     "Value should be #{expected_data_size} bytes, got #{op.value.length}"
      end
    end
  end

  # Test: Different data sizes are respected across phases
  def test_different_data_sizes_are_respected
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "MultiDataSizeTest"},
        "phases": [
          {
            "id": "SIZE_32",
            "description": "Test 32-byte values",
            "connections": 1,
            "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
            "keyspace": {"key_prefix": "s32:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
            "completion": {"type": "requests", "requests": 5}
          },
          {
            "id": "SIZE_256",
            "description": "Test 256-byte values",
            "connections": 1,
            "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 256}],
            "keyspace": {"key_prefix": "s256:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
            "completion": {"type": "requests", "requests": 5}
          },
          {
            "id": "SIZE_1024",
            "description": "Test 1024-byte values",
            "connections": 1,
            "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 1024}],
            "keyspace": {"key_prefix": "s1024:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
            "completion": {"type": "requests", "requests": 5}
          }
        ]
      }
    JSON

    with_temp_metrics_file("multi-data-size") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)

      # Validate via aggregate operations
      all_set_ops = RespBench::Client::Impl::RecordingClient.aggregate_set_operations_with_values
      assert_equal 15, all_set_ops.size, "Should have 15 SET operations (5 + 5 + 5)"

      # Group by prefix and verify sizes
      count32 = 0
      count256 = 0
      count1024 = 0

      all_set_ops.each do |op|
        key = op.key.to_s
        if key.start_with?("s32:")
          assert_equal 32, op.value.length, "32-byte value size"
          count32 += 1
        elsif key.start_with?("s256:")
          assert_equal 256, op.value.length, "256-byte value size"
          count256 += 1
        elsif key.start_with?("s1024:")
          assert_equal 1024, op.value.length, "1024-byte value size"
          count1024 += 1
        end
      end

      assert_equal 5, count32, "Should have 5 32-byte values"
      assert_equal 5, count256, "Should have 5 256-byte values"
      assert_equal 5, count1024, "Should have 5 1024-byte values"
    end
  end

  private

  def recording_driver_config
    parse_driver('{"driver_id": "recording", "mode": "standalone"}')
  end

  def parse_driver(json_string)
    RespBench::Config::ConfigLoader.parse_driver_config_string(json_string)
  end

  def parse_workload(json_string)
    json = JSON.parse(json_string, symbolize_names: true)
    RespBench::Config::ConfigLoader.parse_workload_config(json)
  end

  def with_temp_metrics_file(name)
    Dir.mktmpdir do |dir|
      metrics_file = File.join(dir, "#{name}.ndjson")
      yield metrics_file
    end
  end

  def run_engine(driver_config, workload_config, metrics_file)
    RespBench::Client::Impl::RecordingClient.clear_instances

    engine = RespBench::Engine::BenchmarkEngine.new(
      host: @host,
      port: @port,
      driver_config: driver_config,
      workload_config: workload_config,
      metrics_path: metrics_file
    )
    engine.run
  end

  def parse_metrics(metrics_file)
    content = File.read(metrics_file).strip
    JSON.parse(content)
  end
end
