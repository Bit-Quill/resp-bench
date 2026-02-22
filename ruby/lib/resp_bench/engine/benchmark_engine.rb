# frozen_string_literal: true

require "logger"
require "etc"
require "concurrent"

module RespBench
  module Engine
    # Main benchmark engine that orchestrates the benchmark execution.
    #
    # Supports two concurrency modes:
    #
    # **Process mode** (default, `--concurrency process`):
    #   Forks one OS process per connection (up to MAX_PROCESSES). Each process
    #   has its own GIL, achieving true CPU parallelism. Best for local or
    #   low-latency servers where GIL contention dominates.
    #
    # **Thread mode** (`--concurrency thread`):
    #   Uses one thread per connection within a single process. Suitable for
    #   remote/high-latency servers where I/O wait time >> GIL hold time,
    #   allowing threads to overlap effectively.
    #
    # In process mode, if connections > MAX_PROCESSES, connections are capped
    # at MAX_PROCESSES and a warning is logged.
    class BenchmarkEngine
      DEFAULT_PIPELINE_DEPTH = 1
      MAX_THREADS = 256
      MAX_PROCESSES = 16
      PROGRESS_LOG_INTERVAL_SECONDS = 10

      CONCURRENCY_PROCESS = "process"
      CONCURRENCY_THREAD = "thread"

      def initialize(host:, port:, driver_config:, workload_config:, metrics_path:,
                     commit_id: nil, concurrency_mode: nil, **_options)
        @host = host
        @port = port
        @driver_config = driver_config
        @workload_config = workload_config
        @metrics_writer = Metrics::NdjsonWriter.new(metrics_path)
        @commit_id = commit_id
        @concurrency_mode = resolve_concurrency_mode(concurrency_mode)
        @logger = Logger.new($stdout)
        @logger.level = Logger::INFO
      end

      def run
        @logger.info("Starting benchmark: #{@workload_config.name}")
        @logger.info("Driver: #{@driver_config.driver_id}, Server mode: #{@driver_config.mode}")
        @logger.info("Concurrency: #{concurrency_description}")
        @logger.info("Server: #{@host}:#{@port}")

        setup_metadata

        @workload_config.phases.each do |phase|
          execute_phase(phase)
        end

        @logger.info("Benchmark completed")
      end

      private

      def resolve_concurrency_mode(mode)
        return CONCURRENCY_THREAD if @driver_config.driver_id == "recording"

        case mode&.downcase
        when "thread", "threads"
          CONCURRENCY_THREAD
        when "process", "processes", "fork"
          CONCURRENCY_PROCESS
        when nil, "auto"
          CONCURRENCY_PROCESS # default
        else
          raise ArgumentError, "Unknown concurrency mode: #{mode}. Use 'process' or 'thread'."
        end
      end

      def concurrency_description
        case @concurrency_mode
        when CONCURRENCY_PROCESS
          "multi-process (1 process per connection, max #{MAX_PROCESSES})"
        when CONCURRENCY_THREAD
          "thread-per-client (max #{MAX_THREADS} threads)"
        end
      end

      def setup_metadata
        begin
          sample_client = Client::BenchmarkClientFactory.create_and_connect(@host, @port, @driver_config)

          @metrics_writer.set_metadata(
            commit_id: @commit_id,
            driver_id: @driver_config.driver_id,
            primary_driver_version: sample_client.driver_version,
            secondary_driver_id: @driver_config.secondary_driver_id,
            secondary_driver_version: sample_client.secondary_driver_version
          )

          @logger.info("Metadata: commit=#{@commit_id || 'N/A'}, driver=#{@driver_config.driver_id}, version=#{sample_client.driver_version}")

          sample_client.close
        rescue StandardError => e
          @logger.warn("Failed to get driver version for metadata: #{e.message}")
          @metrics_writer.set_metadata(
            commit_id: @commit_id,
            driver_id: @driver_config.driver_id,
            primary_driver_version: "unknown",
            secondary_driver_id: @driver_config.secondary_driver_id,
            secondary_driver_version: nil
          )
        end
      end

      def execute_phase(phase)
        @logger.info("=== Starting phase: #{phase.id} (#{phase.description}) ===")

        metrics = Metrics::MetricsCollector.new

        if @concurrency_mode == CONCURRENCY_PROCESS && phase.connections > 1
          status = execute_forked_workload(phase, metrics)
        else
          # Thread mode, or single connection (no need to fork for 1 connection)
          pipeline_depth = phase.effective_pipeline_depth
          client_slots = create_client_slots(phase, pipeline_depth)
          commands = Command::CommandFactory.create_all(phase.commands)
          key_generator_seed = phase.keyspace.seed_value
          rate_limiter = phase.rps_limit? ? RateLimiter.create(phase.rps_limit) : nil
          status = execute_threaded_workload(phase, client_slots, commands, phase.keyspace,
                                             key_generator_seed, rate_limiter, metrics)
          close_client_slots(client_slots)
        end

        @metrics_writer.write_phase_results(
          phase_id: phase.id,
          status: status,
          connections: phase.connections,
          collector: metrics
        )

        log_phase_summary(phase, metrics, status)
      end

      # -----------------------------------------------------------------------
      # PROCESS MODE: fork one process per connection
      # -----------------------------------------------------------------------

      def execute_forked_workload(phase, metrics)
        num_connections = phase.connections
        num_processes = [num_connections, MAX_PROCESSES].min

        if num_connections > MAX_PROCESSES
          @logger.warn("Connections (#{num_connections}) exceeds MAX_PROCESSES (#{MAX_PROCESSES}). " \
                       "Using #{MAX_PROCESSES} processes with 1 connection each.")
        end

        @logger.info("Forking #{num_processes} worker processes (1 connection each)...")

        # Create pipes for each worker to send results back
        pipes = num_processes.times.map { IO.pipe }

        # CPS limiter for connection creation
        cps_limiter = phase.cps_limit? ? RateLimiter.create(phase.cps_limit) : nil

        # Fork worker processes - each process handles exactly 1 connection
        child_pids = num_processes.times.map do |proc_idx|
          seed_base = phase.keyspace.seed_value + proc_idx
          # RPS limit divided among processes
          my_rps = phase.rps_limit? ? (phase.rps_limit.to_f / num_processes).ceil : nil

          read_io, write_io = pipes[proc_idx]

          pid = Process.fork do
            # Child process - close read end of our pipe and all other pipes
            read_io.close
            pipes.each_with_index do |(r, w), i|
              next if i == proc_idx
              r.close
              w.close
            end

            run_worker_process(phase, seed_base, my_rps, num_processes, cps_limiter, write_io)
            write_io.close
            exit!(0)
          end

          # Parent - close write end
          write_io.close
          pid
        end

        # Parent: wait for all children and collect results
        metrics.start
        results = []
        start_time = Time.now

        child_pids.each_with_index do |pid, idx|
          read_io = pipes[idx][0]
          begin
            data = read_io.read
            read_io.close
            unless data.nil? || data.empty?
              result = Marshal.load(data)
              results << result if result
            end
          rescue StandardError => e
            @logger.warn("Error reading from worker #{idx}: #{e.message}")
          end
          Process.waitpid(pid)
        end

        metrics.stop
        elapsed = Time.now - start_time

        # Merge all process results into metrics
        total_reqs = 0
        results.each do |result|
          result[:commands].each do |cmd_name, cmd_data|
            merged = metrics.all_metrics[cmd_name]
            unless merged
              merged = Metrics::MetricsCollector::CommandMetrics.new(cmd_name)
              metrics.all_metrics[cmd_name] = merged
            end
            merged.add_raw_counts(cmd_data[:requests], cmd_data[:errors])
            cmd_data[:latencies].each { |l| merged.record_latency(l) }
          end
          total_reqs += result[:total_requests]
        end
        metrics.set_totals_from_processes(results)

        @logger.info("All operations completed (#{total_reqs} total requests, #{num_processes} processes, %.1fs)" % elapsed)
        "COMPLETED"
      rescue Interrupt
        @logger.warn("Workload interrupted - killing workers")
        child_pids&.each { |pid| Process.kill("TERM", pid) rescue nil }
        child_pids&.each { |pid| Process.waitpid(pid) rescue nil }
        metrics.stop
        "INTERRUPTED"
      rescue StandardError => e
        @logger.error("Error in forked workload: #{e.message}\n#{e.backtrace&.first(5)&.join("\n")}")
        child_pids&.each { |pid| Process.kill("TERM", pid) rescue nil }
        child_pids&.each { |pid| Process.waitpid(pid) rescue nil }
        metrics.stop
        "ERROR"
      end

      # Runs inside a forked worker process. Creates exactly 1 connection,
      # runs the request loop, and writes serialized results to the pipe.
      def run_worker_process(phase, seed_base, my_rps, num_processes, cps_limiter, write_io)
        commands = Command::CommandFactory.create_all(phase.commands)
        completion = phase.completion

        # Create exactly 1 connection in this process
        cps_limiter&.acquire
        client = Client::BenchmarkClientFactory.create_and_connect(@host, @port, @driver_config)
        pipeline_depth = phase.effective_pipeline_depth
        slot = ClientSlot.new(client, pipeline_depth)

        # Warmup
        warmup = phase.warmup_requests
        warmup.times { client.ping } if warmup.positive?

        # Rate limiter for this process
        rate_limiter = my_rps ? RateLimiter.create(my_rps) : nil

        # Determine targets - divide total requests among processes
        my_target = nil
        if !completion.duration_based?
          total = completion.total_requests
          my_target = total / num_processes + (total % num_processes > 0 ? 1 : 0)
        end
        end_time = completion.duration_based? ? Time.now + completion.duration_seconds : nil

        # Run single-threaded request loop (no GIL contention!)
        result_data = run_request_loop(slot, commands, phase.keyspace, seed_base,
                                       rate_limiter, my_target, end_time)

        # Close connection
        client.close rescue nil

        # Send results back via pipe
        Marshal.dump(result_data, write_io)
      end

      # Single-threaded request loop. Returns a plain Hash (no default proc)
      # so it can be serialized with Marshal.dump.
      def run_request_loop(slot, commands, keyspace, seed_base,
                           rate_limiter, my_target, end_time)
        key_gen = KeyGenerator.create_with_seed(keyspace, seed_base)
        selector = CommandSelector.new(commands)
        key_wrapper = SingleKeyGenerator.new(nil)
        count = 0
        # Use plain Hash (no default proc) so Marshal.dump works
        cmd_metrics = {}

        if my_target
          while count < my_target
            rate_limiter&.acquire
            command = selector.select
            key_wrapper.key = key_gen.next_key
            begin
              result = command.execute(slot.client, key_wrapper)
              cmd_name = result.command_name
              d = cmd_metrics[cmd_name] ||= { requests: 0, errors: 0, latencies: [] }
              d[:requests] += 1
              if result.success?
                d[:latencies] << [result.latency_micros, 600_000_000].min
              else
                d[:errors] += 1
              end
            rescue StandardError
              cmd_name = command.name
              d = cmd_metrics[cmd_name] ||= { requests: 0, errors: 0, latencies: [] }
              d[:requests] += 1
              d[:errors] += 1
            end
            count += 1
          end
        else
          while Time.now < end_time
            rate_limiter&.acquire
            command = selector.select
            key_wrapper.key = key_gen.next_key
            begin
              result = command.execute(slot.client, key_wrapper)
              cmd_name = result.command_name
              d = cmd_metrics[cmd_name] ||= { requests: 0, errors: 0, latencies: [] }
              d[:requests] += 1
              if result.success?
                d[:latencies] << [result.latency_micros, 600_000_000].min
              else
                d[:errors] += 1
              end
            rescue StandardError
              cmd_name = command.name
              d = cmd_metrics[cmd_name] ||= { requests: 0, errors: 0, latencies: [] }
              d[:requests] += 1
              d[:errors] += 1
            end
            count += 1
          end
        end

        total_errors = cmd_metrics.values.sum { |d| d[:errors] }
        { total_requests: count, total_errors: total_errors, commands: cmd_metrics }
      end

      # -----------------------------------------------------------------------
      # THREAD MODE: thread-per-client in a single process
      # -----------------------------------------------------------------------

      def create_client_slots(phase, pipeline_depth)
        @logger.info("Creating #{phase.connections} connections (pipeline depth: #{pipeline_depth})...")

        slots = []
        cps_limiter = phase.cps_limit? ? RateLimiter.create(phase.cps_limit) : nil

        phase.connections.times do |i|
          cps_limiter&.acquire

          client = Client::BenchmarkClientFactory.create_and_connect(@host, @port, @driver_config)
          slots << ClientSlot.new(client, pipeline_depth)

          @logger.info("Created #{i + 1}/#{phase.connections} connections") if ((i + 1) % 50).zero?
        end

        @logger.info("All #{slots.size} connections established")
        slots
      end

      def execute_threaded_workload(phase, client_slots, commands, keyspace,
                                    key_generator_seed, rate_limiter, metrics)
        completion = phase.completion

        # Calculate thread count (one thread per client, capped at MAX_THREADS)
        thread_count = [client_slots.size, MAX_THREADS].min

        # Assign clients to threads (round-robin if more clients than max threads)
        thread_clients = Array.new(thread_count) { [] }
        client_slots.each_with_index do |slot, idx|
          thread_clients[idx % thread_count] << slot
        end

        # Submit warmup requests BEFORE starting metrics
        warmup_requests = phase.warmup_requests
        submit_warmup(client_slots, warmup_requests) if warmup_requests.positive?

        metrics.start

        # Determine completion condition
        target_requests = completion.duration_based? ? nil : completion.total_requests
        end_time = completion.duration_based? ? Time.now + completion.duration_seconds : nil

        # For request-based completion: divide target evenly among threads
        target_per_thread = nil
        target_remainder = 0
        if target_requests
          target_per_thread = target_requests / thread_count
          target_remainder = target_requests % thread_count
        end

        @logger.info("Starting #{thread_count} worker threads...")

        # Array to collect thread-local metrics from each thread
        thread_collectors = Array.new(thread_count)

        # Shared stop flag - only used for emergency stop (interrupt/error).
        stop_flag = Concurrent::AtomicBoolean.new(false)

        # Start worker threads - each thread runs its own request loop
        workers = thread_count.times.map do |thread_idx|
          assigned_clients = thread_clients[thread_idx]

          local_target = if target_per_thread
                           target_per_thread + (thread_idx < target_remainder ? 1 : 0)
                         end

          Thread.new(thread_idx, assigned_clients, local_target) do |tid, clients, my_target|
            thread_seed = key_generator_seed + tid
            local_key_gen = KeyGenerator.create_with_seed(keyspace, thread_seed)
            local_selector = CommandSelector.new(commands)
            local_collector = metrics.create_thread_collector

            thread_collectors[tid] = local_collector

            key_wrapper = SingleKeyGenerator.new(nil)
            client_idx = 0
            local_count = 0

            if my_target
              while local_count < my_target
                break if stop_flag.value

                rate_limiter&.acquire

                slot = clients[client_idx % clients.size]
                client_idx += 1

                command = local_selector.select
                key_wrapper.key = local_key_gen.next_key

                begin
                  result = command.execute(slot.client, key_wrapper)
                  local_collector.record(result)
                rescue StandardError
                  local_collector.record(
                    Command::CommandResult.new(
                      command_name: command.name,
                      latency_micros: 0,
                      success: false
                    )
                  )
                end

                local_count += 1
              end
            else
              my_end_time = end_time

              while Time.now < my_end_time
                break if stop_flag.value

                rate_limiter&.acquire

                slot = clients[client_idx % clients.size]
                client_idx += 1

                command = local_selector.select
                key_wrapper.key = local_key_gen.next_key

                begin
                  result = command.execute(slot.client, key_wrapper)
                  local_collector.record(result)
                rescue StandardError
                  local_collector.record(
                    Command::CommandResult.new(
                      command_name: command.name,
                      latency_micros: 0,
                      success: false
                    )
                  )
                end

                local_count += 1
              end
            end

            thread_collectors[tid] = local_collector
          end
        end

        # Progress logging in main thread
        begin
          last_log_time = Time.now
          start_time = Time.now

          while workers.any?(&:alive?)
            sleep 0.1
            current_total = thread_collectors.sum { |c| c&.total_requests || 0 }
            last_log_time = log_progress_if_needed(current_total, target_requests, last_log_time, start_time)
          end

          workers.each { |w| w.join(5) }

          metrics.merge_thread_collectors(thread_collectors.compact)

          @logger.info("All operations completed (#{metrics.total_requests} total requests)")
          "COMPLETED"

        rescue Interrupt
          @logger.warn("Workload interrupted")
          stop_flag.value = true
          workers.each { |w| w.join(1) rescue nil }
          metrics.merge_thread_collectors(thread_collectors.compact)
          "INTERRUPTED"
        rescue StandardError => e
          @logger.error("Error during workload execution: #{e.message}")
          @logger.error(e.backtrace&.first(5)&.join("\n"))
          stop_flag.value = true
          workers.each { |w| w.join(1) rescue nil }
          metrics.merge_thread_collectors(thread_collectors.compact)
          "ERROR"
        ensure
          metrics.stop
        end
      end

      # -----------------------------------------------------------------------
      # Shared helpers
      # -----------------------------------------------------------------------

      def log_phase_summary(phase, metrics, status)
        duration_s = metrics.duration_millis / 1000.0
        total = metrics.total_requests
        errors = metrics.total_errors
        rps = duration_s > 0 ? (total / duration_s).round(0) : 0

        @logger.info("=== Phase #{phase.id} completed: #{status} ===")
        @logger.info("  Duration: %.1fs | Requests: %d | Errors: %d | RPS: %d" % [duration_s, total, errors, rps])

        metrics.all_metrics.each do |cmd_name, cmd_metrics|
          next unless cmd_metrics.count > 0

          cmd_rps = duration_s > 0 ? (cmd_metrics.requests / duration_s).round(0) : 0
          @logger.info("  #{cmd_name}: %d req (%d err) | %d req/s | " \
                       "p50=%.0fμs p95=%.0fμs p99=%.0fμs p99.9=%.0fμs | " \
                       "min=%.0fμs max=%.0fμs avg=%.0fμs" % [
                         cmd_metrics.requests, cmd_metrics.errors, cmd_rps,
                         cmd_metrics.p50, cmd_metrics.p95, cmd_metrics.p99, cmd_metrics.p999,
                         cmd_metrics.min, cmd_metrics.max, cmd_metrics.mean
                       ])
        end
      end

      def log_progress_if_needed(current, target, last_log_time, start_time)
        now = Time.now
        return last_log_time if now - last_log_time < PROGRESS_LOG_INTERVAL_SECONDS

        elapsed = now - start_time
        rate = elapsed > 0 ? (current / elapsed).round(0) : 0

        if target
          percent = (current * 100.0 / target).round(1)
          @logger.info("Progress: #{current}/#{target} requests (#{percent}%) - #{rate} req/s")
        else
          @logger.info("Progress: #{current} requests - #{rate} req/s")
        end

        now
      end

      def submit_warmup(client_slots, warmup_requests_per_client)
        total_warmup = client_slots.size * warmup_requests_per_client
        @logger.info("Submitting warmup: #{warmup_requests_per_client} requests per client (#{total_warmup} total)...")

        threads = client_slots.map do |slot|
          Thread.new(slot) do |s|
            warmup_requests_per_client.times do
              s.client.ping
            end
          end
        end

        threads.each(&:join)
        @logger.info("Warmup completed")
      end

      def close_client_slots(client_slots)
        @logger.info("Closing #{client_slots.size} connections...")
        client_slots.each do |slot|
          slot.client.close
        rescue StandardError => e
          @logger.warn("Error closing client: #{e.message}")
        end
      end

      # Wraps a BenchmarkClient with pipeline depth tracking
      class ClientSlot
        attr_reader :client, :pipeline_depth

        def initialize(client, pipeline_depth)
          @client = client
          @pipeline_depth = pipeline_depth
        end
      end

      # Reusable wrapper that returns a pre-generated key once.
      class SingleKeyGenerator
        attr_accessor :key

        def initialize(key)
          @key = key
        end

        def next_key
          @key
        end
      end
    end
  end
end
