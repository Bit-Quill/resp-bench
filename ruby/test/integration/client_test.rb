# frozen_string_literal: true

require_relative "../test_helper"

# Integration tests that require a running Valkey/Redis server.
# Server endpoint can be configured via environment variables:
#   VALKEY_HOST (default: localhost)
#   VALKEY_PORT (default: 6379)
class ClientIntegrationTest < Minitest::Test
  include TestHelper

  def setup
    @host = server_host
    @port = server_port
    @config = RespBench::Config::DriverConfig.new(
      driver_id: "redis-rb",
      mode: "standalone"
    )
  end

  def test_redis_rb_client_connects
    client = RespBench::Client::BenchmarkClientFactory.create_and_connect(@host, @port, @config)

    begin
      assert client.connected?
    ensure
      client.close
    end
  end

  def test_redis_rb_client_ping
    client = RespBench::Client::BenchmarkClientFactory.create_and_connect(@host, @port, @config)

    begin
      result = client.ping
      assert result.success?
      assert_equal "PONG", result.value
      assert result.latency_micros.positive?
    ensure
      client.close
    end
  end

  def test_redis_rb_client_set_get
    client = RespBench::Client::BenchmarkClientFactory.create_and_connect(@host, @port, @config)

    begin
      key = "ruby-integration-test-key"
      value = "test-value-#{Time.now.to_i}"

      # SET
      set_result = client.set(key, value)
      assert set_result.success?
      assert_equal "OK", set_result.value

      # GET
      get_result = client.get(key)
      assert get_result.success?
      assert_equal value, get_result.value

      # Cleanup
      client.del(key)
    ensure
      client.close
    end
  end

  def test_driver_version_returns_string
    client = RespBench::Client::BenchmarkClientFactory.create_and_connect(@host, @port, @config)

    begin
      version = client.driver_version
      assert_kind_of String, version
      refute_empty version
    ensure
      client.close
    end
  end

  # ---- valkey-glide-ruby tests ----

  def test_valkey_glide_client_connects
    config = RespBench::Config::DriverConfig.new(
      driver_id: "valkey-glide-ruby",
      mode: "standalone"
    )
    client = RespBench::Client::BenchmarkClientFactory.create_and_connect(@host, @port, config)

    begin
      assert client.connected?
    ensure
      client.close
    end
  end

  def test_valkey_glide_client_ping
    config = RespBench::Config::DriverConfig.new(
      driver_id: "valkey-glide-ruby",
      mode: "standalone"
    )
    client = RespBench::Client::BenchmarkClientFactory.create_and_connect(@host, @port, config)

    begin
      result = client.ping
      assert result.success?
      assert_equal "PONG", result.value
      assert result.latency_micros.positive?
    ensure
      client.close
    end
  end

  def test_valkey_glide_client_set_get
    config = RespBench::Config::DriverConfig.new(
      driver_id: "valkey-glide-ruby",
      mode: "standalone"
    )
    client = RespBench::Client::BenchmarkClientFactory.create_and_connect(@host, @port, config)

    begin
      key = "ruby-glide-integration-test-key"
      value = "test-value-#{Time.now.to_i}"

      # SET
      set_result = client.set(key, value)
      assert set_result.success?
      assert_equal "OK", set_result.value

      # GET
      get_result = client.get(key)
      assert get_result.success?
      assert_equal value, get_result.value

      # Cleanup
      client.del(key)
    ensure
      client.close
    end
  end

  def test_valkey_glide_driver_version_returns_string
    config = RespBench::Config::DriverConfig.new(
      driver_id: "valkey-glide-ruby",
      mode: "standalone"
    )
    client = RespBench::Client::BenchmarkClientFactory.create_and_connect(@host, @port, config)

    begin
      version = client.driver_version
      assert_kind_of String, version
      refute_empty version
    ensure
      client.close
    end
  end

  private

  def skip_unless_server_available
    begin
      require "socket"
      socket = TCPSocket.new(@host, @port)
      socket.close
    rescue Errno::ECONNREFUSED, Errno::EHOSTUNREACH, SocketError
      skip "Server not available at #{@host}:#{@port}"
    end
  end
end
