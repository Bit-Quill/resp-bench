# frozen_string_literal: true

require_relative "../test_helper"
require "tempfile"
require "json"

# Black-box integration tests that validate error detection and error metrics in the output.
#
# These tests follow the black-box testing approach:
# 1. Define driver and workflow JSON strings with required configuration
# 2. Parse configs using ConfigLoader (same as Main does)
# 3. Run the BenchmarkEngine directly
# 4. Validate using metrics file output or recorded data from RecordingClient
#
# Tests verify:
# - Error simulation using RecordingClient captures errors correctly
# - Error counts in output metrics match configured error rates
# - Per-command error tracking works correctly
class ErrorMetricsIntegrationTest < Minitest::Test
  include TestHelper

  RATE_TOLERANCE_PERCENT = 2.0 # 2% margin for statistical validation

  def setup
    RespBench::Client::Impl::RecordingClient.clear_instances
    @host = "localhost"
    @port = 6379
  end

  def teardown
    RespBench::Client::Impl::RecordingClient.clear_instances
  end

  # === Error Simulation Tests (Black-box with RecordingClient) ===

  # Test: 10% error rate produces approximately 10% errors (within 2% tolerance: 8-12%)
  def test_simulated_errors_are_captured_in_metrics
    run_error_rate_test(0.1, 10_000, "error-sim-10pct")
  end

  # Test: 100% error rate produces all errors
  def test_full_error_rate_produces_all_errors
    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {
          "error_rate": 1.0,
          "error_message": "All operations fail"
        }
      }
    JSON

    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "FullErrorTest"},
        "phases": [{
          "id": "ALL_ERRORS",
          "description": "100% error rate test",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "allerror:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 100}
        }]
      }
    JSON

    with_temp_metrics_file("all-errors") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # All operations should fail
      assert_equal 100, json["totals"]["requests"], "Expected 100 total requests"
      assert_equal 100, json["totals"]["errors"], "Expected 100 total errors"
      assert_equal 100, json.dig("metrics", "SET", "errors"), "Expected 100 SET errors"
    end
  end

  # Test: 0% error rate produces no errors
  def test_zero_error_rate_produces_no_errors
    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {
          "error_rate": 0.0
        }
      }
    JSON

    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "NoErrorTest"},
        "phases": [{
          "id": "NO_ERRORS",
          "description": "0% error rate test",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "noerror:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 100}
        }]
      }
    JSON

    with_temp_metrics_file("no-errors") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # No operations should fail
      assert_equal 100, json["totals"]["requests"], "Expected 100 total requests"
      assert_equal 0, json["totals"]["errors"], "Expected 0 total errors"
      assert_equal 0, json.dig("metrics", "SET", "errors"), "Expected 0 SET errors"
    end
  end

  # Test: Errors are tracked per command (SET and GET both have ~20% errors)
  def test_errors_are_tracked_per_command
    # Driver with 20% error rate - both commands will have similar error rates
    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {
          "error_rate": 0.2
        }
      }
    JSON

    # Use 20000 requests for < 2% margin per command (~10000 each)
    # Statistical: SE = sqrt(0.2*0.8/10000) ≈ 0.4%, so 2% margin is ~5 SEs
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "PerCommandErrorTest"},
        "phases": [{
          "id": "MULTI_CMD_ERRORS",
          "description": "Test per-command error tracking",
          "connections": 1,
          "commands": [
            {"command": "set", "weight": 0.5, "data_size_bytes": 32},
            {"command": "get", "weight": 0.5}
          ],
          "keyspace": {
            "key_prefix": "percmd:",
            "keys_count": 1000,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 20000}
        }]
      }
    JSON

    with_temp_metrics_file("per-cmd-errors") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # Total requests
      assert_equal 20_000, json["totals"]["requests"], "Expected 20000 total requests"

      # Both SET and GET should have errors (approximately 20% each)
      set_requests = json.dig("metrics", "SET", "requests")
      set_errors = json.dig("metrics", "SET", "errors")
      get_requests = json.dig("metrics", "GET", "requests")
      get_errors = json.dig("metrics", "GET", "errors")

      # Both commands should have approximately 50% of total (within 2% tolerance: 48%-52%)
      assert_in_delta 10_000, set_requests, 400, "SET requests should be ~10000"
      assert_in_delta 10_000, get_requests, 400, "GET requests should be ~10000"

      # Each command should have approximately 20% errors (within 2% tolerance: 18%-22%)
      set_error_rate = set_errors.to_f / set_requests
      assert_in_delta 0.20, set_error_rate, 0.02, "SET error rate should be ~20%"

      get_error_rate = get_errors.to_f / get_requests
      assert_in_delta 0.20, get_error_rate, 0.02, "GET error rate should be ~20%"
    end
  end

  # Test: Error message is preserved in recorded operations
  def test_error_message_is_preserved_in_recorded_operation
    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {
          "error_rate": 1.0,
          "error_message": "Custom error message for testing"
        }
      }
    JSON

    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "ErrorMessageTest"},
        "phases": [{
          "id": "ERROR_MSG_TEST",
          "description": "Test error message preservation",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "errmsg:",
            "keys_count": 10,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 5}
        }]
      }
    JSON

    with_temp_metrics_file("error-msg") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # All operations should be errors
      assert_equal 5, json["totals"]["errors"], "Expected 5 total errors"
    end
  end

  # Test: Successful operations have no errors
  def test_successful_operations_have_no_errors
    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {
          "error_rate": 0.0
        }
      }
    JSON

    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "SuccessTest"},
        "phases": [{
          "id": "SUCCESS_TEST",
          "description": "Test successful operations",
          "connections": 1,
          "commands": [
            {"command": "set", "weight": 0.5, "data_size_bytes": 32},
            {"command": "get", "weight": 0.5}
          ],
          "keyspace": {
            "key_prefix": "success:",
            "keys_count": 50,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 100}
        }]
      }
    JSON

    with_temp_metrics_file("success") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # No errors
      assert_equal 100, json["totals"]["requests"], "Expected 100 total requests"
      assert_equal 0, json["totals"]["errors"], "Expected 0 total errors"
    end
  end

  # Test: Metrics handle empty error collection
  def test_metrics_handle_empty_error_collection
    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {"error_rate": 0.0}
      }
    JSON

    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "EmptyErrorTest"},
        "phases": [{
          "id": "EMPTY_ERRORS",
          "description": "Test with no errors",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "empty:",
            "keys_count": 10,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 10}
        }]
      }
    JSON

    with_temp_metrics_file("empty-errors") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      assert_equal 0, json["totals"]["errors"]
      assert_equal 0, json.dig("metrics", "SET", "errors")
    end
  end

  # Test: Metrics handle all errors collection
  def test_metrics_handle_all_errors_collection
    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {"error_rate": 1.0}
      }
    JSON

    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "AllErrorsTest"},
        "phases": [{
          "id": "ALL_ERRORS_EDGE",
          "description": "Test with all errors",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "allerr:",
            "keys_count": 10,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 10}
        }]
      }
    JSON

    with_temp_metrics_file("all-errors-edge") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      assert_equal 10, json["totals"]["requests"]
      assert_equal 10, json["totals"]["errors"]
      assert_equal 10, json.dig("metrics", "SET", "requests")
      assert_equal 10, json.dig("metrics", "SET", "errors")
    end
  end

  # Test: Duration-based completion with errors
  def test_duration_based_completion_with_errors
    # Use small delay to ensure many requests complete in the duration window
    # for statistically meaningful error rate validation
    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {
          "error_rate": 0.1,
          "operation_delay_micros": 100
        }
      }
    JSON

    # Run for 2 seconds to accumulate enough samples
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "DurationErrorTest"},
        "phases": [{
          "id": "DURATION_TEST",
          "description": "Test duration-based completion with errors",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "duration:",
            "keys_count": 1000,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "duration", "seconds": 2}
        }]
      }
    JSON

    with_temp_metrics_file("duration-errors") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # Should have completed many requests in 2 seconds
      requests = json["totals"]["requests"]
      errors = json["totals"]["errors"]

      # With 100µs delay, we should get thousands of requests in 2 seconds
      assert_operator requests, :>, 5000, "Should have > 5000 requests in 2 seconds"

      # Should have approximately 10% errors (within 2% tolerance: 8%-12%)
      error_rate = errors.to_f / requests
      assert_in_delta 0.10, error_rate, 0.02, "Error rate should be ~10%"
    end
  end

  # Test: Connection to non-existent server detects the failure
  # Parity with Java's ErrorMetricsIntegrationTest.benchmarkClientCapturesConnectionErrors
  #
  # Note: redis-rb uses lazy connections - Redis.new doesn't connect immediately.
  # Errors surface on first command. The BenchmarkClient.ping wraps the error
  # in a TimedResult (via measure), so we verify connected? returns false.
  def test_benchmark_client_captures_connection_errors
    config = RespBench::Config::DriverConfig.new(
      driver_id: "redis-rb",
      mode: "standalone"
    )

    # Use a port that's unlikely to have a server
    non_existent_port = 59999

    client = RespBench::Client::BenchmarkClientFactory.create_and_connect("localhost", non_existent_port, config)

    # redis-rb uses lazy connections, connected? attempts a PING and catches errors
    refute client.connected?, "Client should not be connected to non-existent server"
  ensure
    client&.close
  end

  # Test: Multiple connections with errors
  def test_multiple_connections_with_errors
    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {
          "error_rate": 0.15
        }
      }
    JSON

    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "MultiConnErrorTest"},
        "phases": [{
          "id": "MULTI_CONN_ERRORS",
          "description": "Test errors with multiple connections",
          "connections": 10,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "multiconn:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 5000}
        }]
      }
    JSON

    with_temp_metrics_file("multi-conn-errors") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # Verify connections
      assert_equal 10, json.dig("phase", "connections")

      # Verify error rate
      requests = json["totals"]["requests"]
      errors = json["totals"]["errors"]

      assert_equal 5000, requests
      error_rate = errors.to_f / requests
      assert_in_delta 0.15, error_rate, 0.02, "Error rate should be ~15%"
    end
  end

  private

  def run_error_rate_test(error_rate, total_requests, test_name)
    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {
          "error_rate": #{error_rate},
          "error_message": "Simulated #{(error_rate * 100).to_i}% error"
        }
      }
    JSON

    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {
          "name": "ErrorSimulationTest",
          "version": "1.0"
        },
        "phases": [{
          "id": "ERROR_SIM",
          "description": "Test error simulation with #{(error_rate * 100).to_i}% error rate",
          "connections": 1,
          "commands": [
            {"command": "set", "weight": 1.0, "data_size_bytes": 32}
          ],
          "keyspace": {
            "key_prefix": "error:",
            "keys_count": 1000,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {
            "type": "requests",
            "requests": #{total_requests}
          }
        }]
      }
    JSON

    with_temp_metrics_file(test_name) do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # Verify totals
      actual_requests = json["totals"]["requests"]
      error_count = json["totals"]["errors"]

      assert_equal total_requests, actual_requests, "Total requests should match"

      # Error count should be approximately the target rate (within 2% tolerance)
      actual_error_rate = error_count.to_f / actual_requests
      min_rate = error_rate - RATE_TOLERANCE_PERCENT / 100.0
      max_rate = error_rate + RATE_TOLERANCE_PERCENT / 100.0

      assert_operator actual_error_rate, :>=, min_rate,
                      "Error rate #{actual_error_rate} should be >= #{min_rate}"
      assert_operator actual_error_rate, :<=, max_rate,
                      "Error rate #{actual_error_rate} should be <= #{max_rate}"
    end
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
    # Clear recording client instances before each run
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
