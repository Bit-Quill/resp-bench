# frozen_string_literal: true

require "json"
require "base64"
require "fileutils"
require "time"

module RespBench
  module Metrics
    # Writes benchmark metrics to NDJSON format (Newline Delimited JSON).
    # Each phase is written as a single JSON line.
    class NdjsonWriter
      def initialize(path)
        @output_path = path
        @commit_id = nil
        @driver_id = nil
        @primary_driver_version = nil
        @secondary_driver_id = nil
        @secondary_driver_version = nil
      end

      # Set metadata for the benchmark run
      def set_metadata(commit_id:, driver_id:, primary_driver_version:,
                       secondary_driver_id: nil, secondary_driver_version: nil)
        @commit_id = commit_id
        @driver_id = driver_id
        @primary_driver_version = primary_driver_version
        @secondary_driver_id = secondary_driver_id
        @secondary_driver_version = secondary_driver_version
      end

      # Write phase results as a single NDJSON line
      def write_phase_results(phase_id:, status:, connections:, collector:)
        # Ensure parent directory exists
        FileUtils.mkdir_p(File.dirname(@output_path))

        json = build_phase_json(phase_id, status, connections, collector)

        File.open(@output_path, "a") do |f|
          f.puts(JSON.generate(json))
        end
      end

      private

      def build_phase_json(phase_id, status, connections, collector)
        result = {}

        # Metadata
        if @commit_id || @driver_id
          metadata = {}
          metadata[:commit_id] = @commit_id if @commit_id
          metadata[:timestamp] = Time.now.utc.iso8601
          metadata[:driver_id] = @driver_id if @driver_id
          metadata[:primary_driver_version] = @primary_driver_version if @primary_driver_version
          metadata[:secondary_driver_id] = @secondary_driver_id if @secondary_driver_id
          metadata[:secondary_driver_version] = @secondary_driver_version if @secondary_driver_version
          result[:metadata] = metadata
        end

        # Phase info
        result[:phase] = {
          id: phase_id,
          status: status,
          start_timestamp: collector.start_time&.utc&.iso8601,
          finish_timestamp: collector.end_time&.utc&.iso8601,
          duration_ms: collector.duration_millis,
          connections: connections
        }

        # Totals
        result[:totals] = {
          requests: collector.total_requests,
          errors: collector.total_errors
        }

        # Per-command metrics
        result[:metrics] = build_command_metrics(collector)

        result
      end

      def build_command_metrics(collector)
        metrics = {}

        collector.all_metrics.each do |cmd_name, cmd_metrics|
          cmd_data = {
            requests: cmd_metrics.requests,
            errors: cmd_metrics.errors,
            latency: {
              unit: "us",
              count: cmd_metrics.count,
              summary: {
                min: cmd_metrics.min.to_i,
                p50: cmd_metrics.p50.to_i,
                p95: cmd_metrics.p95.to_i,
                p99: cmd_metrics.p99.to_i,
                p999: cmd_metrics.p999.to_i,
                max: cmd_metrics.max.to_i
              }
            }
          }

          # Add HDR histogram if available
          histogram = cmd_metrics.histogram
          if histogram
            cmd_data[:latency][:hdr] = {
              format: "hdr",
              sigfig: 3,
              payload_b64: encode_histogram(histogram)
            }
          end

          metrics[cmd_name] = cmd_data
        end

        metrics
      end

      def encode_histogram(histogram)
        # Encode histogram to base64 using HDRHistogram's native format
        # This should be compatible with Java's HdrHistogram library
        begin
          encoded = histogram.encode
          Base64.strict_encode64(encoded)
        rescue StandardError => e
          # If encoding fails, return empty string
          ""
        end
      end
    end
  end
end
