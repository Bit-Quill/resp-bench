# frozen_string_literal: true

require "concurrent"

# HDRHistogram gem v0.1.11 (latest) uses the deprecated Data_Wrap_Struct C API
# combined with rb_define_singleton_method("new"), which triggers a harmless
# "undefining the allocator of T_DATA class HDRHistogram" warning on Ruby 3.2+.
# This is an upstream gem issue - the C extension needs to migrate to
# TypedData_Wrap_Struct + rb_define_alloc_func. The warning is cosmetic only;
# the gem functions correctly. We suppress it during require and instantiation.
orig_verbose = $VERBOSE
$VERBOSE = nil
require "HDRHistogram"
$VERBOSE = orig_verbose

module RespBench
  module Metrics
    # Collects metrics for benchmark operations using HdrHistogram.
    #
    # Supports two modes:
    # 1. Direct recording (thread-safe, uses mutex) - for simple/single-thread use
    # 2. Per-thread collection with merge - for high-performance multi-thread use
    #
    # For multi-threaded benchmarks, use create_thread_collector() to get a
    # lock-free ThreadLocalCollector for each worker thread, then call
    # merge_thread_collectors() after all threads complete to combine results.
    class MetricsCollector
      # Lock-free per-command metrics for use within a single thread.
      # No mutex, no atomic operations - pure local state.
      class ThreadLocalCommandMetrics
        attr_reader :command_name, :requests, :errors

        def initialize(command_name)
          @command_name = command_name
          @requests = 0
          @errors = 0
          @histogram = create_histogram
        end

        def record(result)
          @requests += 1
          if result.success?
            latency = result.latency_micros
            latency = 600_000_000 if latency > 600_000_000
            @histogram.record(latency)
          else
            @errors += 1
          end
        end

        def histogram
          @histogram
        end

        def count
          @histogram.count
        end

        private

        def create_histogram
          # Suppress C extension warning about T_DATA allocator on first creation
          old_verbose = $VERBOSE
          $VERBOSE = nil
          HDRHistogram.new(1, 600_000_000, 3)
        ensure
          $VERBOSE = old_verbose
        end
      end

      # Lock-free collector for a single worker thread.
      # Each thread gets one of these - no synchronization needed.
      class ThreadLocalCollector
        attr_reader :total_requests, :total_errors

        def initialize
          @command_metrics = {}
          @total_requests = 0
          @total_errors = 0
        end

        def record(result)
          @total_requests += 1
          @total_errors += 1 unless result.success?

          metrics = @command_metrics[result.command_name]
          unless metrics
            metrics = ThreadLocalCommandMetrics.new(result.command_name)
            @command_metrics[result.command_name] = metrics
          end
          metrics.record(result)
        end

        def command_metrics
          @command_metrics
        end
      end

      # Merged command metrics (read-only, used after merge).
      # Exposes the same interface as the old CommandMetrics for NdjsonWriter compatibility.
      class CommandMetrics
        attr_reader :command_name

        def initialize(command_name)
          @command_name = command_name
          @requests = 0
          @errors = 0
          @histogram = nil
          @mutex = Mutex.new
        end

        # Record a single result (thread-safe, for direct recording mode)
        def record(result)
          @requests += 1
          if result.success?
            @mutex.synchronize do
              @histogram ||= create_histogram
              latency = [result.latency_micros, 600_000_000].min
              @histogram.record(latency)
            end
          else
            @errors += 1
          end
        end

        # Merge a ThreadLocalCommandMetrics into this CommandMetrics
        def merge_from(thread_local_metrics)
          @requests += thread_local_metrics.requests
          @errors += thread_local_metrics.errors
          src_hist = thread_local_metrics.histogram
          if src_hist && src_hist.count > 0
            @histogram ||= create_histogram
            @histogram.merge!(src_hist)
          end
        end

        # Add raw request/error counts (for cross-process merge)
        def add_raw_counts(requests, errors)
          @requests += requests
          @errors += errors
        end

        # Record a single latency value into histogram (for cross-process merge)
        def record_latency(latency_micros)
          @histogram ||= create_histogram
          @histogram.record(latency_micros)
        end

        def requests
          @requests
        end

        def errors
          @errors
        end

        def histogram
          @histogram
        end

        def min
          @histogram&.min || 0
        end

        def max
          @histogram&.max || 0
        end

        def mean
          @histogram&.mean || 0.0
        end

        def p50
          @histogram&.percentile(50) || 0
        end

        def p90
          @histogram&.percentile(90) || 0
        end

        def p95
          @histogram&.percentile(95) || 0
        end

        def p99
          @histogram&.percentile(99) || 0
        end

        def p999
          @histogram&.percentile(99.9) || 0
        end

        def count
          @histogram&.count || 0
        end

        private

        def create_histogram
          old_verbose = $VERBOSE
          $VERBOSE = nil
          HDRHistogram.new(1, 600_000_000, 3)
        ensure
          $VERBOSE = old_verbose
        end
      end

      def initialize
        @command_metrics = {}
        @metrics_mutex = Mutex.new
        @total_requests = 0
        @total_errors = 0
        @start_time = nil
        @end_time = nil
      end

      def start
        @start_time = Time.now
      end

      def stop
        @end_time = Time.now
      end

      # Create a lock-free thread-local collector for a worker thread.
      # Call this once per thread before the hot loop starts.
      def create_thread_collector
        ThreadLocalCollector.new
      end

      # Merge all thread-local collectors into this MetricsCollector.
      # Call this AFTER all worker threads have completed (single-threaded merge).
      def merge_thread_collectors(collectors)
        collectors.each do |collector|
          @total_requests += collector.total_requests
          @total_errors += collector.total_errors

          collector.command_metrics.each do |cmd_name, thread_metrics|
            merged = @command_metrics[cmd_name]
            unless merged
              merged = CommandMetrics.new(cmd_name)
              @command_metrics[cmd_name] = merged
            end
            merged.merge_from(thread_metrics)
          end
        end
      end

      # Direct recording (thread-safe, for simple/single-thread use).
      # For multi-threaded use, prefer create_thread_collector + merge_thread_collectors.
      def record(result)
        @total_requests += 1
        @total_errors += 1 unless result.success?

        metrics = nil
        @metrics_mutex.synchronize do
          metrics = @command_metrics[result.command_name]
          unless metrics
            metrics = CommandMetrics.new(result.command_name)
            @command_metrics[result.command_name] = metrics
          end
        end
        metrics.record(result)
      end

      def total_requests
        @total_requests
      end

      def total_errors
        @total_errors
      end

      def start_time
        @start_time
      end

      def end_time
        @end_time
      end

      def duration_millis
        return 0 unless @start_time && @end_time

        ((@end_time - @start_time) * 1000).to_i
      end

      def metrics(command_name)
        @command_metrics[command_name]
      end

      def all_metrics
        @command_metrics
      end

      # Set total counts from process-level results (for fork-based merge)
      def set_totals_from_processes(results)
        @total_requests = results.sum { |r| r[:total_requests] }
        @total_errors = results.sum { |r| r[:total_errors] }
      end

      def reset
        @command_metrics.clear
        @total_requests = 0
        @total_errors = 0
        @start_time = nil
        @end_time = nil
      end
    end
  end
end
