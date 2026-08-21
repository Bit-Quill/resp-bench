# frozen_string_literal: true

require "valkey"

module RespBench
  module Client
    module Impl
      # Benchmark client implementation using valkey-glide-ruby gem.
      # valkey-glide-ruby is a drop-in replacement for redis-rb,
      # using the Valkey class instead of Redis.
      class ValkeyGlideClient < BenchmarkClient
        def initialize
          @client = nil
          @config = nil
        end

        def connect(host, port, config)
          @config = config

          options = {
            host: host,
            port: port,
            timeout: 500 # request timeout in milliseconds (protobuf uint32)
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
                      Valkey.new(cluster: ["valkey://#{host}:#{port}"], **options.except(:host, :port))
                    else
                      Valkey.new(**options)
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
          # The Gemfile pins an exact released version of valkey-glide-rb, so the
          # gem's own VERSION constant identifies the driver under measurement.
          # (This used to look up a git-sourced spec named "valkey" to report a
          # commit SHA; that gem name was never published by
          # valkey-io/valkey-glide-ruby, so the lookup could not have matched.)
          Valkey::VERSION
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
