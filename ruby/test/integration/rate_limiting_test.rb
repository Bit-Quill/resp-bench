# frozen_string_literal: true

require_relative "../test_helper"
require "tempfile"
require "json"

# Black-box integration tests that validate rate limiting functionality (cps_limit and rps_limit).
#
# These tests follow the black-box testing approach:
# 1. Define driver and workflow JSON strings with required configuration
# 2. Parse configs using ConfigLoader (same as Main does)
# 3. Run the BenchmarkEngine directly with recording client
# 4. Validate using NDJSON metrics output (duration, request counts)
#
# All tests use the recording client which has no network latency, making rate limiting
# the dominant factor in execution time.
#
# Rate limit tolerance is set to 5% to ensure reliable rate control. The minimum reliable
# sleep period is 50ms, so rate limits of 20/sec (50ms between ops) are used for precise testing.
class RateLimitingTest < Minitest::Test
  include TestHelper

  RATE_TOLERANCE_PERCENT = 5.0 # 5% margin for rate limiting validation

  def setup
    RespBench::Client::Impl::RecordingClient.clear_instances
    @host = "localhost"
    @port = 6379
  end

  def teardown
    RespBench::Client::Impl::RecordingClient.clear_instances
  end

  # === RPS Limit Tests (Requests Per Second) ===

  # Test: RPS limit controls request rate
  def test_rps_limit_controls_request_rate
    # 20 rps = 50ms between operations (minimum reliable sleep period)
    # 3 second duration = ~60 expected requests
    target_rps = 20
    duration_seconds = 3
    expected_requests = target_rps * duration_seconds

    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "RpsLimitTest"},
        "phases": [{
          "id": "RPS_LIMITED",
          "description": "Test RPS rate limiting",
          "connections": 1,
          "rps_limit": #{target_rps},
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "rps:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "duration", "seconds": #{duration_seconds}}
        }]
      }
    JSON

    with_temp_metrics_file("rps-limit") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      total_requests = json.dig("totals", "requests")
      duration_ms = json.dig("phase", "duration_ms")

      # Calculate actual rate
      actual_rate = total_requests.to_f / (duration_ms / 1000.0)

      # Assert rate is within 5% of target
      assert_in_delta target_rps, actual_rate, target_rps * RATE_TOLERANCE_PERCENT / 100.0,
                      "Actual rate #{actual_rate} should be within 5% of target #{target_rps}"

      # Assert request count is approximately expected
      assert_in_delta expected_requests, total_requests, expected_requests * RATE_TOLERANCE_PERCENT / 100.0,
                      "Total requests should be approximately #{expected_requests}"
    end
  end

  # Test: RPS limit with request-based completion
  def test_rps_limit_with_request_based_completion
    # 20 rps with 60 requests = expected ~3 seconds duration
    target_rps = 20
    target_requests = 60
    expected_duration_ms = (target_requests * 1000) / target_rps

    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "RpsRequestBasedTest"},
        "phases": [{
          "id": "RPS_REQUEST_BASED",
          "description": "Test RPS limit with request-based completion",
          "connections": 1,
          "rps_limit": #{target_rps},
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "rpsreq:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{target_requests}}
        }]
      }
    JSON

    with_temp_metrics_file("rps-request-based") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      total_requests = json.dig("totals", "requests")
      duration_ms = json.dig("phase", "duration_ms")

      # Assert exact request count
      assert_equal target_requests, total_requests

      # Assert duration is within 5% of expected
      assert_in_delta expected_duration_ms, duration_ms, expected_duration_ms * RATE_TOLERANCE_PERCENT / 100.0,
                      "Duration #{duration_ms}ms should be within 5% of expected #{expected_duration_ms}ms"

      # Verify rate
      actual_rate = total_requests.to_f / (duration_ms / 1000.0)
      assert_in_delta target_rps, actual_rate, target_rps * RATE_TOLERANCE_PERCENT / 100.0,
                      "Actual rate #{actual_rate} should be within 5% of target #{target_rps}"
    end
  end

  # Test: Multiple connections with shared RPS limit
  def test_multiple_connections_with_shared_rps_limit
    # 20 rps shared across 4 connections
    # Total throughput should still be ~20 rps (not 20 per connection)
    target_rps = 20
    connections = 4
    duration_seconds = 3
    expected_requests = target_rps * duration_seconds

    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "MultiConnRpsTest"},
        "phases": [{
          "id": "MULTI_CONN_RPS",
          "description": "Test RPS limit shared across multiple connections",
          "connections": #{connections},
          "rps_limit": #{target_rps},
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "multiconn:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "duration", "seconds": #{duration_seconds}}
        }]
      }
    JSON

    with_temp_metrics_file("multi-conn-rps") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      total_requests = json.dig("totals", "requests")
      duration_ms = json.dig("phase", "duration_ms")
      actual_connections = json.dig("phase", "connections")

      # Verify connections were created
      assert_equal connections, actual_connections

      # Calculate actual rate (should be ~20 rps total, not 80)
      actual_rate = total_requests.to_f / (duration_ms / 1000.0)

      # Assert rate is within 5% of target (shared limit)
      assert_in_delta target_rps, actual_rate, target_rps * RATE_TOLERANCE_PERCENT / 100.0,
                      "Actual rate #{actual_rate} should be within 5% of shared target #{target_rps}"
    end
  end

  # Test: CPS limit controls connection rate
  def test_cps_limit_controls_connection_rate
    # 10 cps = 100ms between connections
    # 20 connections = expected ~2 seconds for connection establishment
    # With 10 requests (fast with no delay), total time should be dominated by connection time
    target_cps = 10
    connections = 20
    requests = 10 # Small number, fast completion
    expected_connection_time_ms = (connections * 1000) / target_cps

    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "CpsLimitTest"},
        "phases": [{
          "id": "CPS_LIMITED",
          "description": "Test CPS rate limiting",
          "connections": #{connections},
          "cps_limit": #{target_cps},
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "cps:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{requests}}
        }]
      }
    JSON

    with_temp_metrics_file("cps-limit") do |metrics_file|
      # Measure wall clock time (includes connection establishment + request execution)
      start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC)
      run_engine(driver_config, workload_config, metrics_file)
      total_wall_clock_ms = ((Process.clock_gettime(Process::CLOCK_MONOTONIC) - start_time) * 1000).to_i

      json = parse_metrics(metrics_file)
      total_requests = json.dig("totals", "requests")
      request_duration_ms = json.dig("phase", "duration_ms")
      actual_connections = json.dig("phase", "connections")

      # Verify connections count in output
      assert_equal connections, actual_connections
      assert_equal requests, total_requests

      # Wall clock time = connection time + request time
      # Request time should be very small (no rate limit, recording client)
      # So total time should be dominated by connection establishment
      # Expected: ~2 sec connection + ~0 sec requests = ~2 sec total
      assert_operator total_wall_clock_ms, :>=, (expected_connection_time_ms * 0.95).to_i,
                      "Total time #{total_wall_clock_ms}ms should be >= expected connection time #{expected_connection_time_ms}ms"

      # Request phase should be fast (no rps limit)
      assert_operator request_duration_ms, :<, 500,
                      "Request phase should be fast (< 500ms) without RPS limit"
    end
  end

  # Test: No rate limit allows maximum throughput
  def test_no_rate_limit_allows_maximum_throughput
    # No rps_limit (or -1) = unlimited throughput
    # Recording client has no network latency, so should complete very fast
    target_requests = 1000

    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "NoRateLimitTest"},
        "phases": [{
          "id": "NO_LIMIT",
          "description": "Test unlimited throughput without rate limit",
          "connections": 1,
          "rps_limit": -1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "nolimit:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{target_requests}}
        }]
      }
    JSON

    with_temp_metrics_file("no-limit") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      total_requests = json.dig("totals", "requests")
      duration_ms = json.dig("phase", "duration_ms")

      # Assert all requests completed
      assert_equal target_requests, total_requests

      # Assert very fast completion (should be < 1 second for 1000 ops with no delay)
      assert_operator duration_ms, :<, 1000,
                      "Duration #{duration_ms}ms should be fast (< 1s) without rate limiting"

      # Calculate actual rate - should be very high
      actual_rate = total_requests.to_f / (duration_ms / 1000.0)
      assert_operator actual_rate, :>, 1000,
                      "Actual rate #{actual_rate} should be high (> 1000 rps) without rate limiting"
    end
  end

  # Test: No rate limit is much faster than rate-limited
  def test_no_rate_limit_much_faster_than_rate_limited
    # Compare execution time: rate-limited vs unlimited
    # This demonstrates that rate limiting is actually working
    target_requests = 100
    rps_limit = 20

    # First run: with rate limit
    rate_limited_workload = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "RateLimitedComparison"},
        "phases": [{
          "id": "RATE_LIMITED_COMPARE",
          "description": "Rate limited comparison",
          "connections": 1,
          "rps_limit": #{rps_limit},
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "compare:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{target_requests}}
        }]
      }
    JSON

    driver_config1 = recording_driver_config
    rate_limited_duration_ms = nil

    with_temp_metrics_file("compare-limited") do |metrics_file|
      run_engine(driver_config1, rate_limited_workload, metrics_file)
      json = parse_metrics(metrics_file)
      rate_limited_duration_ms = json.dig("phase", "duration_ms")
    end

    # Clear instances for second run
    RespBench::Client::Impl::RecordingClient.clear_instances

    # Second run: without rate limit
    unlimited_workload = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "UnlimitedComparison"},
        "phases": [{
          "id": "UNLIMITED_COMPARE",
          "description": "Unlimited comparison",
          "connections": 1,
          "rps_limit": -1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "compare:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{target_requests}}
        }]
      }
    JSON

    driver_config2 = recording_driver_config
    unlimited_duration_ms = nil

    with_temp_metrics_file("compare-unlimited") do |metrics_file|
      run_engine(driver_config2, unlimited_workload, metrics_file)
      json = parse_metrics(metrics_file)
      unlimited_duration_ms = json.dig("phase", "duration_ms")
    end

    # Rate limited should take ~5 seconds (100 reqs at 20 rps)
    expected_rate_limited_ms = (target_requests * 1000) / rps_limit
    assert_in_delta expected_rate_limited_ms, rate_limited_duration_ms,
                    expected_rate_limited_ms * RATE_TOLERANCE_PERCENT / 100.0,
                    "Rate-limited duration #{rate_limited_duration_ms}ms should be ~#{expected_rate_limited_ms}ms"

    # Unlimited should be MUCH faster (at least 10x faster)
    assert_operator unlimited_duration_ms, :<, rate_limited_duration_ms / 10,
                    "Unlimited duration #{unlimited_duration_ms}ms should be much faster than rate-limited #{rate_limited_duration_ms}ms"
  end

  # Test: Combined CPS and RPS limits work together
  def test_combined_cps_and_rps_limits_work_together
    # Both CPS and RPS limits active
    # CPS limit controls connection creation rate
    # RPS limit controls request rate after connections are established
    target_cps = 10
    target_rps = 20
    connections = 10
    target_requests = 40

    # Expected: ~1 sec for connections (10 at 10/sec), ~2 sec for requests (40 at 20/sec)
    expected_connection_time_ms = (connections * 1000) / target_cps
    expected_request_time_ms = (target_requests * 1000) / target_rps
    expected_total_time_ms = expected_connection_time_ms + expected_request_time_ms

    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "CombinedLimitsTest"},
        "phases": [{
          "id": "COMBINED_LIMITS",
          "description": "Test combined CPS and RPS limits",
          "connections": #{connections},
          "cps_limit": #{target_cps},
          "rps_limit": #{target_rps},
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "combined:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{target_requests}}
        }]
      }
    JSON

    with_temp_metrics_file("combined-limits") do |metrics_file|
      # Measure wall clock time (includes connection establishment + request execution)
      start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC)
      run_engine(driver_config, workload_config, metrics_file)
      total_wall_clock_ms = ((Process.clock_gettime(Process::CLOCK_MONOTONIC) - start_time) * 1000).to_i

      json = parse_metrics(metrics_file)
      total_requests = json.dig("totals", "requests")
      request_duration_ms = json.dig("phase", "duration_ms")
      actual_connections = json.dig("phase", "connections")

      # Assert all requests completed
      assert_equal target_requests, total_requests
      assert_equal connections, actual_connections

      # Verify RPS was respected during request phase
      # duration_ms is for request phase only
      assert_in_delta expected_request_time_ms, request_duration_ms,
                      expected_request_time_ms * RATE_TOLERANCE_PERCENT / 100.0,
                      "Request phase duration #{request_duration_ms}ms should be within 5% of expected #{expected_request_time_ms}ms"

      # Verify total time includes both CPS and RPS limited phases
      # Total = connection time (~1s) + request time (~2s) = ~3s
      assert_in_delta expected_total_time_ms, total_wall_clock_ms,
                      expected_total_time_ms * RATE_TOLERANCE_PERCENT / 100.0,
                      "Total time #{total_wall_clock_ms}ms should be within 5% of expected #{expected_total_time_ms}ms (connection + request)"
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
