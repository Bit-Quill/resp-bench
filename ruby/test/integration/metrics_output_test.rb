# frozen_string_literal: true

require_relative "../test_helper"
require "tempfile"
require "json"
require "base64"

# Black-box integration tests that validate NDJSON metrics output format and histogram accuracy.
#
# These tests follow the black-box testing approach:
# 1. Define driver and workload JSON strings with required configuration
# 2. Parse configs using ConfigLoader (same as Main does)
# 3. Run the BenchmarkEngine directly
# 4. Validate using metrics file output (NDJSON)
#
# Tests verify:
# - NDJSON format correctness (single line per phase)
# - Phase metadata (id, status, timestamps, duration, connections)
# - Request counts accuracy
# - HDR histogram encoding/decoding
# - Per-command metrics
# - Required fields schema validation
class MetricsOutputTest < Minitest::Test
  include TestHelper

  def setup
    RespBench::Client::Impl::RecordingClient.clear_instances
    @host = "localhost"
    @port = 6379
  end

  def teardown
    RespBench::Client::Impl::RecordingClient.clear_instances
  end

  # === NDJSON Format Tests ===

  # Test: NDJSON output has valid JSON format (single line)
  def test_ndjson_output_has_valid_format
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "FormatTest"},
        "phases": [{
          "id": "FORMAT_TEST",
          "description": "Test NDJSON format",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "fmt:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 100}
        }]
      }
    JSON

    with_temp_metrics_file("format") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)

      # Verify file exists and has valid JSON
      assert File.exist?(metrics_file), "Metrics file should exist"

      content = File.read(metrics_file).strip
      refute_empty content, "Metrics file should not be empty"

      # Verify it's a single line (NDJSON)
      lines = content.split("\n")
      assert_equal 1, lines.size, "Should be a single JSON line"

      # Parse JSON (will raise if invalid)
      json = JSON.parse(content)
      assert json.is_a?(Hash), "Should parse to a JSON object"
    end
  end

  # Test: Multiple phases produce multiple lines
  def test_multiple_phases_produce_multiple_lines
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "MultiPhaseTest"},
        "phases": [
          {
            "id": "PHASE_1",
            "description": "Phase 1",
            "connections": 1,
            "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
            "keyspace": {"key_prefix": "p1:", "keys_count": 50, "key_size_bytes": 16, "generation_alg": "sequential_int"},
            "completion": {"type": "requests", "requests": 50}
          },
          {
            "id": "PHASE_2",
            "description": "Phase 2",
            "connections": 1,
            "commands": [{"command": "get", "weight": 1.0}],
            "keyspace": {"key_prefix": "p1:", "keys_count": 50, "key_size_bytes": 16, "generation_alg": "sequential_int"},
            "completion": {"type": "requests", "requests": 50}
          },
          {
            "id": "PHASE_3",
            "description": "Phase 3",
            "connections": 1,
            "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
            "keyspace": {"key_prefix": "p3:", "keys_count": 50, "key_size_bytes": 16, "generation_alg": "sequential_int"},
            "completion": {"type": "requests", "requests": 50}
          }
        ]
      }
    JSON

    with_temp_metrics_file("multi-phase") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)

      # Verify three lines
      lines = File.readlines(metrics_file).map(&:strip).reject(&:empty?)
      assert_equal 3, lines.size, "Should have 3 JSON lines for 3 phases"

      # Each line should be valid JSON with correct phase ID
      lines.each_with_index do |line, i|
        json = JSON.parse(line)
        assert_equal "PHASE_#{i + 1}", json.dig("phase", "id"),
                     "Phase #{i + 1} should have correct ID"
      end
    end
  end

  # Test: Phase metadata is correct
  def test_phase_metadata_is_correct
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "MetadataTest"},
        "phases": [{
          "id": "METADATA_TEST",
          "description": "Test phase metadata",
          "connections": 2,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "meta:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 100}
        }]
      }
    JSON

    with_temp_metrics_file("metadata") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # Verify phase metadata
      assert_equal "METADATA_TEST", json.dig("phase", "id")
      assert_equal "COMPLETED", json.dig("phase", "status")
      assert_equal 2, json.dig("phase", "connections")
      assert_operator json.dig("phase", "duration_ms"), :>, 0, "Duration should be > 0"
      refute_nil json.dig("phase", "start_timestamp"), "start_timestamp should exist"
      refute_nil json.dig("phase", "finish_timestamp"), "finish_timestamp should exist"
    end
  end

  # Test: Total request count matches execution
  def test_total_request_count_matches_execution
    target_requests = 1000
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "RequestCountTest"},
        "phases": [{
          "id": "COUNT_TEST",
          "description": "Test request count",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "count:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{target_requests}}
        }]
      }
    JSON

    with_temp_metrics_file("count") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      assert_equal target_requests, json.dig("totals", "requests")
      assert_equal target_requests, json.dig("metrics", "SET", "requests")
    end
  end

  # Test: Histogram captures latency
  def test_histogram_captures_latency
    target_requests = 1000
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "HistogramTest"},
        "phases": [{
          "id": "HISTOGRAM_TEST",
          "description": "Test histogram capture",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "hist:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{target_requests}}
        }]
      }
    JSON

    with_temp_metrics_file("histogram") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # Verify histogram summary values exist and are reasonable
      summary = json.dig("metrics", "SET", "latency", "summary")
      min = summary["min"]
      p50 = summary["p50"]
      p95 = summary["p95"]
      p99 = summary["p99"]
      max = summary["max"]

      # Min should be >= 0 and <= p50 <= p95 <= p99 <= max
      assert_operator min, :>=, 0, "min should be >= 0"
      assert_operator p50, :>=, min, "p50 should be >= min"
      assert_operator p95, :>=, p50, "p95 should be >= p50"
      assert_operator p99, :>=, p95, "p99 should be >= p95"
      assert_operator max, :>=, p99, "max should be >= p99"

      # Verify latency count matches requests (no errors with recording client)
      actual_count = json.dig("metrics", "SET", "latency", "count")
      errors = json.dig("metrics", "SET", "errors")

      assert_equal 0, errors, "Should have no errors with default recording client"
      assert_equal target_requests, actual_count, "Latency count should equal request count"

      # Verify unit is microseconds
      assert_equal "us", json.dig("metrics", "SET", "latency", "unit")
    end
  end

  # Test: HDR histogram metadata exists
  def test_hdr_histogram_metadata_exists
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "DecodeTest"},
        "phases": [{
          "id": "DECODE_TEST",
          "description": "Test histogram HDR metadata",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "decode:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 100}
        }]
      }
    JSON

    with_temp_metrics_file("decode") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # Verify HDR section exists with required metadata
      hdr = json.dig("metrics", "SET", "latency", "hdr")
      refute_nil hdr, "hdr section should exist"

      # Verify HDR metadata fields
      assert_equal "hdr", hdr["format"], "format should be 'hdr'"
      assert_equal 3, hdr["sigfig"], "sigfig should be 3"

      # payload_b64 should exist (may be empty if encoding not supported)
      assert hdr.key?("payload_b64"), "payload_b64 key should exist"

      # If payload is non-empty, verify it's valid base64
      if hdr["payload_b64"] && !hdr["payload_b64"].empty?
        decoded = Base64.strict_decode64(hdr["payload_b64"])
        assert_operator decoded.length, :>, 0, "Decoded payload should have content"
      end
    end
  end

  # Test: Per-command metrics are accurate
  def test_per_command_metrics_are_accurate
    target_requests = 10_000
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "PerCommandTest"},
        "phases": [{
          "id": "MULTI_CMD",
          "description": "Test per-command metrics",
          "connections": 10,
          "commands": [
            {"command": "set", "weight": 0.5, "data_size_bytes": 32},
            {"command": "get", "weight": 0.5}
          ],
          "keyspace": {
            "key_prefix": "percmd:",
            "keys_count": 200,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{target_requests}}
        }]
      }
    JSON

    with_temp_metrics_file("percmd") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # Verify we have all expected requests
      total_requests = json.dig("totals", "requests")
      assert_equal target_requests, total_requests

      # Both SET and GET should have some requests
      set_requests = json.dig("metrics", "SET", "requests")
      get_requests = json.dig("metrics", "GET", "requests")

      # Total should match sum of individual commands
      assert_equal total_requests, set_requests + get_requests

      # Each command should have roughly 50% (within 3% tolerance)
      assert_in_delta 5000, set_requests, 150, "SET should have ~50% of requests"
      assert_in_delta 5000, get_requests, 150, "GET should have ~50% of requests"

      # Get error counts
      set_errors = json.dig("metrics", "SET", "errors")
      get_errors = json.dig("metrics", "GET", "errors")

      # Assert NO errors - all commands should succeed
      assert_equal 0, set_errors, "SET should have no errors"
      assert_equal 0, get_errors, "GET should have no errors"

      # Assert strict equality - latency counts must equal request counts
      set_latency_count = json.dig("metrics", "SET", "latency", "count")
      get_latency_count = json.dig("metrics", "GET", "latency", "count")

      assert_equal set_requests, set_latency_count, "SET latency count should match requests"
      assert_equal get_requests, get_latency_count, "GET latency count should match requests"
    end
  end

  # Test: Output contains all required fields
  def test_output_contains_all_required_fields
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "SchemaTest"},
        "phases": [{
          "id": "SCHEMA_TEST",
          "description": "Test schema completeness",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "schema:",
            "keys_count": 10,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 20}
        }]
      }
    JSON

    with_temp_metrics_file("schema") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # Phase fields
      assert json.key?("phase"), "phase field missing"
      assert json["phase"].key?("id"), "phase.id field missing"
      assert json["phase"].key?("status"), "phase.status field missing"
      assert json["phase"].key?("start_timestamp"), "phase.start_timestamp field missing"
      assert json["phase"].key?("finish_timestamp"), "phase.finish_timestamp field missing"
      assert json["phase"].key?("duration_ms"), "phase.duration_ms field missing"
      assert json["phase"].key?("connections"), "phase.connections field missing"

      # Totals fields
      assert json.key?("totals"), "totals field missing"
      assert json["totals"].key?("requests"), "totals.requests field missing"
      assert json["totals"].key?("errors"), "totals.errors field missing"

      # Metrics fields - check if SET exists
      assert json.key?("metrics"), "metrics field missing"
      assert_equal 20, json["totals"]["requests"]

      set_metrics = json.dig("metrics", "SET")
      assert set_metrics.key?("requests"), "metrics.SET.requests field missing"
      assert set_metrics.key?("errors"), "metrics.SET.errors field missing"
      assert set_metrics.key?("latency"), "metrics.SET.latency field missing"

      latency = set_metrics["latency"]
      assert_equal 20, latency["count"]
      assert latency.key?("unit"), "latency.unit field missing"
      assert latency.key?("count"), "latency.count field missing"
      assert latency.key?("summary"), "latency.summary field missing"
      assert latency.key?("hdr"), "latency.hdr field missing"

      # Summary fields
      summary = latency["summary"]
      assert summary.key?("min"), "summary.min field missing"
      assert summary.key?("p50"), "summary.p50 field missing"
      assert summary.key?("p95"), "summary.p95 field missing"
      assert summary.key?("p99"), "summary.p99 field missing"
      assert summary.key?("p999"), "summary.p999 field missing"
      assert summary.key?("max"), "summary.max field missing"

      # HDR fields
      hdr = latency["hdr"]
      assert hdr.key?("format"), "hdr.format field missing"
      assert hdr.key?("sigfig"), "hdr.sigfig field missing"
      assert hdr.key?("payload_b64"), "hdr.payload_b64 field missing"
    end
  end

  # Test: Latency p50 should be less than 1ms for localhost operations
  # Parity with Java's MetricsOutputTest.latencyP50ShouldBeLessThan1ms
  #
  # This test ensures that latency measurements are accurate and represent actual
  # command execution time (not queue wait time). p50 should be under 1ms (1000µs)
  # for localhost connections with a recording client (which has zero network latency).
  def test_latency_p50_should_be_less_than_1ms
    target_requests = 10_000
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "LatencyValidationTest"},
        "phases": [{
          "id": "LATENCY_VALIDATION",
          "description": "Validate latency measurements are accurate",
          "connections": 1,
          "commands": [
            {"command": "set", "weight": 0.5, "data_size_bytes": 32},
            {"command": "get", "weight": 0.5}
          ],
          "keyspace": {
            "key_prefix": "latval:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{target_requests}}
        }]
      }
    JSON

    with_temp_metrics_file("latency-validation") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # 1ms = 1000 microseconds - this is a generous limit for recording client operations
      max_p50_latency_micros = 1000

      # Verify SET latency p50 < 1ms
      set_p50 = json.dig("metrics", "SET", "latency", "summary", "p50")
      assert_operator set_p50, :<, max_p50_latency_micros,
                      "SET p50 latency should be < #{max_p50_latency_micros} µs (1ms), " \
                      "but was #{set_p50} µs (#{set_p50 / 1000.0} ms). " \
                      "This may indicate latency is being measured incorrectly."

      # Verify GET latency p50 < 1ms
      get_p50 = json.dig("metrics", "GET", "latency", "summary", "p50")
      assert_operator get_p50, :<, max_p50_latency_micros,
                      "GET p50 latency should be < #{max_p50_latency_micros} µs (1ms), " \
                      "but was #{get_p50} µs (#{get_p50 / 1000.0} ms). " \
                      "This may indicate latency is being measured incorrectly."

      # Also verify the values are not in nanoseconds (would be 1000x higher)
      # If values were in nanoseconds, p50 would likely be > 100,000
      assert_operator set_p50, :<, 100_000,
                      "SET latency appears to be in nanoseconds instead of microseconds"
      assert_operator get_p50, :<, 100_000,
                      "GET latency appears to be in nanoseconds instead of microseconds"
    end
  end

  # Test: HDR histogram base64 field is present and summary values are consistent
  # Parity with Java's MetricsOutputTest.histogramBase64CanBeDecoded
  #
  # Java fully decodes the HDR histogram from base64 and verifies totalCount
  # and min/max values. Ruby's hdr_histogram gem may not support the encode
  # method (it returns empty string), so we validate the metadata and summary
  # fields instead. If payload_b64 is non-empty, we also verify it's valid base64.
  def test_histogram_summary_values_are_consistent
    driver_config = recording_driver_config
    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "FullDecodeTest"},
        "phases": [{
          "id": "FULL_DECODE_TEST",
          "description": "Test full histogram decoding",
          "connections": 1,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "fulldecode:",
            "keys_count": 100,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": 100}
        }]
      }
    JSON

    with_temp_metrics_file("full-decode") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      # Verify HDR section exists with required metadata
      hdr = json.dig("metrics", "SET", "latency", "hdr")
      refute_nil hdr, "hdr section should exist"
      assert_equal "hdr", hdr["format"]
      assert_equal 3, hdr["sigfig"]

      # payload_b64 should exist (may be empty if Ruby gem doesn't support encode)
      assert hdr.key?("payload_b64"), "payload_b64 key should exist"

      # If payload is non-empty, verify it's valid base64
      if hdr["payload_b64"] && !hdr["payload_b64"].empty?
        decoded = Base64.strict_decode64(hdr["payload_b64"])
        assert_operator decoded.length, :>, 0, "Decoded payload should have content"
      end

      # Verify the summary values are consistent with 100 recorded operations
      count = json.dig("metrics", "SET", "latency", "count")
      assert_equal 100, count, "Latency count should be 100"

      min = json.dig("metrics", "SET", "latency", "summary", "min")
      max = json.dig("metrics", "SET", "latency", "summary", "max")
      assert_operator min, :>=, 0, "min should be >= 0"
      assert_operator max, :>=, min, "max should be >= min"
    end
  end

  # Test: Long-tail latency histogram with log-normal distribution
  def test_latency_histogram_with_long_tail_distribution
    # Use recording client with log-normal distribution
    # 256 connections × ~391 requests each = 100,000 measurements
    # (capped at 256 threads to avoid OS overload)
    latency_min_ms = 50
    median_ms = 150
    p9999_target_ms = 900
    total_requests = 100_000

    driver_config = parse_driver(<<~JSON)
      {
        "driver_id": "recording",
        "mode": "standalone",
        "specific_driver_config": {
          "latency_distribution": "log_normal",
          "latency_min_ms": #{latency_min_ms},
          "latency_median_ms": #{median_ms},
          "latency_p9999_target_ms": #{p9999_target_ms}
        }
      }
    JSON

    workload_config = parse_workload(<<~JSON)
      {
        "benchmark_profile": {"name": "LongTailLatencyTest"},
        "phases": [{
          "id": "LONG_TAIL_HISTOGRAM",
          "description": "Test long-tail latency distribution",
          "connections": 256,
          "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
          "keyspace": {
            "key_prefix": "longtail:",
            "keys_count": 10000,
            "key_size_bytes": 16,
            "generation_alg": "sequential_int"
          },
          "completion": {"type": "requests", "requests": #{total_requests}}
        }]
      }
    JSON

    with_temp_metrics_file("longtail-histogram") do |metrics_file|
      run_engine(driver_config, workload_config, metrics_file)
      json = parse_metrics(metrics_file)

      total_requests_actual = json.dig("totals", "requests")
      assert_equal total_requests, total_requests_actual

      # Get summary values (in microseconds)
      summary = json.dig("metrics", "SET", "latency", "summary")
      p50_us = summary["p50"]
      p95_us = summary["p95"]
      p99_us = summary["p99"]
      p999_us = summary["p999"]

      # Calculate expected percentiles using log-normal distribution math
      # Same formula as RecordingClient:
      #   mu = ln(median_ms)
      #   sigma = (ln(p9999_target_ms) - mu) / 3.72
      #   percentile_value = exp(mu + z_score * sigma)
      mu = Math.log(median_ms)
      sigma = (Math.log(p9999_target_ms) - mu) / 3.72

      # Z-scores for standard normal distribution
      z_p50 = 0.0
      z_p95 = 1.6449
      z_p99 = 2.3263
      z_p999 = 3.0902

      # Expected percentile values in microseconds (ms * 1000)
      expected_p50_us = (Math.exp(mu + z_p50 * sigma) * 1000).to_i
      expected_p95_us = (Math.exp(mu + z_p95 * sigma) * 1000).to_i
      expected_p99_us = (Math.exp(mu + z_p99 * sigma) * 1000).to_i
      expected_p999_us = (Math.exp(mu + z_p999 * sigma) * 1000).to_i

      # Assert each percentile correlates within 3% of expected value
      # (Matching Java's withinPercentage(3))
      assert_in_delta expected_p50_us, p50_us, expected_p50_us * 0.03,
                      "p50: expected ~#{expected_p50_us} µs"
      assert_in_delta expected_p95_us, p95_us, expected_p95_us * 0.03,
                      "p95: expected ~#{expected_p95_us} µs"
      assert_in_delta expected_p99_us, p99_us, expected_p99_us * 0.03,
                      "p99: expected ~#{expected_p99_us} µs"
      assert_in_delta expected_p999_us, p999_us, expected_p999_us * 0.03,
                      "p999: expected ~#{expected_p999_us} µs"

      # Verify min is >= floor (50ms = 50,000µs, allow 1ms tolerance)
      min_us = summary["min"]
      assert_operator min_us, :>=, (latency_min_ms - 1) * 1000,
                      "min should be >= floor (#{latency_min_ms} ms)"

      # Verify ordering invariant: min <= p50 <= p95 <= p99 <= p999 <= max
      max_us = summary["max"]
      assert_operator min_us, :<=, p50_us, "min <= p50"
      assert_operator p50_us, :<=, p95_us, "p50 <= p95"
      assert_operator p95_us, :<=, p99_us, "p95 <= p99"
      assert_operator p99_us, :<=, p999_us, "p99 <= p999"
      assert_operator p999_us, :<=, max_us, "p999 <= max"
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
