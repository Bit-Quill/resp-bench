# frozen_string_literal: true

require "redis"

module RespBench
  module Client
    module Impl
      # Benchmark client implementation using redis-rb gem.
      class RedisRbClient < BenchmarkClient
        def initialize
          @client = nil
          @config = nil
        end

        def connect(host, port, config)
          @config = config

          options = {
            host: host,
            port: port
          }

          # Handle TLS configuration
          if config.tls
            options[:ssl] = true
            options[:ssl_params] = build_ssl_params(config.tls)
          end

          # Handle authentication
          if config.auth
            options[:password] = config.auth["password"] if config.auth["password"]
            options[:username] = config.auth["username"] if config.auth["username"]
          end

          # Create client based on mode
          @client = case config.mode
                    when "cluster"
                      Redis.new(cluster: ["redis://#{host}:#{port}"], **options.except(:host, :port))
                    else
                      Redis.new(**options)
                    end
        end

        def connected?
          return false unless @client

          begin
            @client.ping == "PONG"
          rescue StandardError
            false
          end
        end

        def ping
          measure { @client.ping }
        end

        def get(key)
          measure { @client.get(key) }
        end

        def set(key, value)
          measure { @client.set(key, value) }
        end

        def del(key)
          measure { @client.del(key) }
        end

        def close
          @client&.close
          @client = nil
        end

        def driver_version
          Redis::VERSION
        end

        private

        def build_ssl_params(tls_config)
          params = {}
          params[:ca_file] = tls_config["ca_cert_path"] if tls_config["ca_cert_path"]
          params[:cert] = OpenSSL::X509::Certificate.new(File.read(tls_config["cert_path"])) if tls_config["cert_path"]
          params[:key] = OpenSSL::PKey::RSA.new(File.read(tls_config["key_path"])) if tls_config["key_path"]
          params[:verify_mode] = tls_config["verify_peer"] ? OpenSSL::SSL::VERIFY_PEER : OpenSSL::SSL::VERIFY_NONE
          params
        end
      end
    end
  end
end
