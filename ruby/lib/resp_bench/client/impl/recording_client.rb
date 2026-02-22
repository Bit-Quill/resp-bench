# frozen_string_literal: true

require "securerandom"

module RespBench
  module Client
    module Impl
      # Recording client for testing. Records all operations for verification.
      # Supports configurable latency simulation and error injection for comprehensive testing.
      #
      # Configuration via specific_driver_config in JSON:
      #   {
      #     "driver_id": "recording",
      #     "mode": "standalone",
      #     "specific_driver_config": {
      #       "operation_delay_micros": 5000,
      #       "delay_variation_micros": 500,
      #       "error_rate": 0.1,
      #       "error_message": "Simulated error"
      #     }
      #   }
      #
      # For latency histogram testing with long-tail distribution:
      #   {
      #     "driver_id": "recording",
      #     "mode": "standalone",
      #     "specific_driver_config": {
      #       "latency_distribution": "log_normal",
      #       "latency_min_ms": 100,
      #       "latency_median_ms": 150,
      #       "latency_p9999_target_ms": 900
      #     }
      #   }
      #
      class RecordingClient < BenchmarkClient
        # Recorded operation structure
        RecordedOperation = Struct.new(:command, :timestamp, :key, :value, :success, :error_message, keyword_init: true)

        # Class-level storage for aggregate operations (survives instance close)
        @@instances = []
        @@instances_mutex = Mutex.new
        @@aggregate_operations = []
        @@aggregate_mutex = Mutex.new

        class << self
          # Get all active RecordingClient instances
          # @return [Array<RecordingClient>] list of active instances
          def instances
            @@instances_mutex.synchronize { @@instances.dup }
          end

          # Get the most recently created instance
          # @return [RecordingClient, nil] the last instance or nil if none
          def last_instance
            @@instances_mutex.synchronize { @@instances.last }
          end

          # Clear all registered instances and aggregate operations
          # Should be called in test setup/teardown
          def clear_instances
            @@instances_mutex.synchronize { @@instances.clear }
            @@aggregate_mutex.synchronize { @@aggregate_operations.clear }
          end

          # Get all aggregate operations from all instances (survives instance close)
          # @return [Array<RecordedOperation>] list of all recorded operations
          def aggregate_operations
            @@aggregate_mutex.synchronize { @@aggregate_operations.dup }
          end

          # Get aggregate operations filtered by command type
          # @param command [String] command type (SET, GET, PING, DEL, FLUSHDB)
          # @return [Array<RecordedOperation>] list of matching operations
          def aggregate_operations_by_command(command)
            @@aggregate_mutex.synchronize do
              @@aggregate_operations.select { |op| op.command == command }
            end
          end

          # Get all aggregate SET operations with their values
          # @return [Array<RecordedOperation>] list of SET operations with values
          def aggregate_set_operations_with_values
            @@aggregate_mutex.synchronize do
              @@aggregate_operations.select { |op| op.command == "SET" && !op.value.nil? }
            end
          end

          # Get all unique keys from aggregate operations
          # @return [Array<String>] list of unique key strings
          def aggregate_unique_keys
            @@aggregate_mutex.synchronize do
              @@aggregate_operations.filter_map(&:key).uniq
            end
          end
        end

        attr_reader :operations, :stored_data, :connect_time
        attr_reader :operation_delay_micros, :delay_variation_micros
        attr_reader :latency_distribution, :latency_min_ms, :log_normal_mu, :log_normal_sigma
        attr_reader :error_rate, :error_message

        def initialize
          @operations = []
          @operations_mutex = Mutex.new
          @stored_data = {}
          @data_mutex = Mutex.new
          @connected = false
          @connect_time = nil

          # Counters
          @set_count = 0
          @get_count = 0
          @ping_count = 0
          @del_count = 0
          @counter_mutex = Mutex.new

          # Delay simulation settings (fixed mode)
          @operation_delay_micros = 0
          @delay_variation_micros = 0

          # Log-normal latency distribution settings
          @latency_distribution = "fixed" # "fixed" or "log_normal"
          @latency_min_ms = 0
          @log_normal_mu = 0.0
          @log_normal_sigma = 0.0

          # Error simulation settings
          @error_rate = 0.0
          @error_message = "Simulated error"
        end

        # @see BenchmarkClient#connect
        def connect(host, port, config)
          @connect_time = Time.now.to_i * 1000
          @connected = true

          # Read configuration from specific_driver_config
          if config&.specific_driver_config
            cfg = config.specific_driver_config

            # Read operation delay (fixed mode)
            @operation_delay_micros = cfg[:operation_delay_micros].to_i if cfg[:operation_delay_micros]

            # Read delay variation (fixed mode)
            @delay_variation_micros = cfg[:delay_variation_micros].to_i if cfg[:delay_variation_micros]

            # Read error rate
            @error_rate = cfg[:error_rate].to_f if cfg[:error_rate]

            # Read error message
            @error_message = cfg[:error_message].to_s if cfg[:error_message]

            # Read latency distribution settings
            @latency_distribution = cfg[:latency_distribution].to_s if cfg[:latency_distribution]

            if @latency_distribution == "log_normal"
              # Read log-normal parameters
              @latency_min_ms = cfg[:latency_min_ms].to_i if cfg[:latency_min_ms]

              median_ms = cfg[:latency_median_ms]&.to_i || 150
              p9999_target_ms = cfg[:latency_p9999_target_ms]&.to_i || 900

              # Calculate log-normal parameters from median and p9999 target
              # For log-normal: median = exp(mu), so mu = ln(median)
              # p9999 corresponds to z-score ≈ 3.72, so: ln(p9999) = mu + 3.72 * sigma
              @log_normal_mu = Math.log(median_ms)
              @log_normal_sigma = (Math.log(p9999_target_ms) - @log_normal_mu) / 3.72

              # Ensure sigma is positive
              @log_normal_sigma = 0.5 if @log_normal_sigma <= 0
            end
          end

          record_operation("CONNECT", nil, nil, true, nil)

          # Register this instance
          @@instances_mutex.synchronize { @@instances << self }
        end

        # @see BenchmarkClient#connected?
        def connected?
          @connected
        end

        # @see BenchmarkClient#ping
        def ping
          increment_counter(:ping)
          start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond)

          simulate_delay

          latency_micros = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond) - start_time
          success = !should_simulate_error?
          error = success ? nil : @error_message

          record_operation("PING", nil, nil, success, error)

          if success
            TimedResult.new(value: "PONG", latency_micros: latency_micros)
          else
            TimedResult.new(value: nil, latency_micros: latency_micros, error: RuntimeError.new(@error_message))
          end
        end

        # @see BenchmarkClient#get
        def get(key)
          increment_counter(:get)
          start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond)

          simulate_delay

          latency_micros = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond) - start_time
          success = !should_simulate_error?
          error = success ? nil : @error_message

          record_operation("GET", key, nil, success, error)

          if success
            value = @data_mutex.synchronize { @stored_data[key] }
            TimedResult.new(value: value, latency_micros: latency_micros)
          else
            TimedResult.new(value: nil, latency_micros: latency_micros, error: RuntimeError.new(@error_message))
          end
        end

        # @see BenchmarkClient#set
        def set(key, value)
          increment_counter(:set)
          start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond)

          simulate_delay

          latency_micros = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond) - start_time
          success = !should_simulate_error?
          error = success ? nil : @error_message

          if success
            @data_mutex.synchronize { @stored_data[key] = value }
          end

          record_operation("SET", key, value, success, error)

          if success
            TimedResult.new(value: "OK", latency_micros: latency_micros)
          else
            TimedResult.new(value: nil, latency_micros: latency_micros, error: RuntimeError.new(@error_message))
          end
        end

        # @see BenchmarkClient#del
        def del(key)
          increment_counter(:del)
          start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond)

          simulate_delay

          latency_micros = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond) - start_time
          success = !should_simulate_error?
          error = success ? nil : @error_message

          count = 0
          if success
            @data_mutex.synchronize do
              count = 1 if @stored_data.delete(key)
            end
          end

          record_operation("DEL", key, nil, success, error)

          if success
            TimedResult.new(value: count, latency_micros: latency_micros)
          else
            TimedResult.new(value: nil, latency_micros: latency_micros, error: RuntimeError.new(@error_message))
          end
        end

        # Execute FLUSHDB command
        # @return [TimedResult<nil>] result
        def flush_db
          start_time = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond)

          simulate_delay

          latency_micros = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond) - start_time
          @data_mutex.synchronize { @stored_data.clear }

          record_operation("FLUSHDB", nil, nil, true, nil)

          TimedResult.new(value: "OK", latency_micros: latency_micros)
        end

        # @see BenchmarkClient#close
        def close
          @connected = false
          record_operation("CLOSE", nil, nil, true, nil)

          # Unregister this instance
          @@instances_mutex.synchronize { @@instances.delete(self) }
        end

        # @see BenchmarkClient#driver_version
        def driver_version
          "1.0.0"
        end

        # === Counter methods ===

        def set_count
          @counter_mutex.synchronize { @set_count }
        end

        def get_count
          @counter_mutex.synchronize { @get_count }
        end

        def ping_count
          @counter_mutex.synchronize { @ping_count }
        end

        def del_count
          @counter_mutex.synchronize { @del_count }
        end

        def total_operations
          @counter_mutex.synchronize { @set_count + @get_count + @ping_count + @del_count }
        end

        # === Operation retrieval methods ===

        # Get operations filtered by command type
        # @param command [String] command type (SET, GET, PING, DEL, FLUSHDB)
        # @return [Array<RecordedOperation>] list of matching operations
        def operations_by_command(command)
          @operations_mutex.synchronize do
            @operations.select { |op| op.command == command }
          end
        end

        # Get all successful operations
        # @return [Array<RecordedOperation>] list of successful operations
        def successful_operations
          @operations_mutex.synchronize do
            @operations.select(&:success)
          end
        end

        # Get all failed operations
        # @return [Array<RecordedOperation>] list of failed operations
        def failed_operations
          @operations_mutex.synchronize do
            @operations.reject(&:success)
          end
        end

        # Get all unique keys that were accessed
        # @return [Array<String>] list of unique key strings
        def unique_keys
          @operations_mutex.synchronize do
            @operations.filter_map(&:key).uniq
          end
        end

        # Get all SET operations with their values
        # @return [Array<RecordedOperation>] list of SET operations with values
        def set_operations_with_values
          @operations_mutex.synchronize do
            @operations.select { |op| op.command == "SET" && !op.value.nil? }
          end
        end

        # Reset the client state
        def reset
          @operations_mutex.synchronize { @operations.clear }
          @counter_mutex.synchronize do
            @set_count = 0
            @get_count = 0
            @ping_count = 0
            @del_count = 0
          end
          @data_mutex.synchronize { @stored_data.clear }

          @operation_delay_micros = 0
          @delay_variation_micros = 0
          @latency_distribution = "fixed"
          @latency_min_ms = 0
          @log_normal_mu = 0.0
          @log_normal_sigma = 0.0
          @error_rate = 0.0
          @error_message = "Simulated error"
        end

        private

        def increment_counter(type)
          @counter_mutex.synchronize do
            case type
            when :set then @set_count += 1
            when :get then @get_count += 1
            when :ping then @ping_count += 1
            when :del then @del_count += 1
            end
          end
        end

        def record_operation(command, key, value, success, error_message)
          op = RecordedOperation.new(
            command: command,
            timestamp: (Time.now.to_f * 1000).to_i,
            key: key,
            value: value,
            success: success,
            error_message: error_message
          )

          @operations_mutex.synchronize { @operations << op }
          @@aggregate_mutex.synchronize { @@aggregate_operations << op }
        end

        def simulate_delay
          delay_ms = calculate_delay_ms
          return if delay_ms <= 0

          sleep(delay_ms / 1000.0)
        end

        def calculate_delay_ms
          if @latency_distribution == "log_normal"
            # Generate log-normal sample: exp(mu + sigma * Z) where Z is standard normal
            # Box-Muller transform to generate standard normal
            u1 = rand
            u2 = rand
            z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math::PI * u2)

            sample_ms = Math.exp(@log_normal_mu + @log_normal_sigma * z)
            # Ensure minimum latency for OS sleep safety
            [@latency_min_ms, sample_ms.to_i].max
          else
            # Fixed delay mode (original behavior)
            return 0 if @operation_delay_micros <= 0

            actual_delay_micros = @operation_delay_micros
            if @delay_variation_micros > 0
              variation = rand(-@delay_variation_micros..@delay_variation_micros)
              actual_delay_micros = [0, @operation_delay_micros + variation].max
            end
            actual_delay_micros / 1000 # Convert micros to millis
          end
        end

        def should_simulate_error?
          return false if @error_rate <= 0.0
          return true if @error_rate >= 1.0

          rand < @error_rate
        end
      end
    end
  end
end
