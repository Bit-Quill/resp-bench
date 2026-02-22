# frozen_string_literal: true

require "json"

module RespBench
  module Config
    # Loads configuration from JSON files.
    class ConfigLoader
      class << self
        # Load driver configuration from a JSON file
        def load_driver_config(path)
          json = parse_json_file(path)
          parse_driver_config(json)
        end

        # Load workload configuration from a JSON file
        def load_workload_config(path)
          json = parse_json_file(path)
          parse_workload_config(json)
        end

        # Parse driver configuration from a JSON string
        def parse_driver_config_string(json_string)
          json = JSON.parse(json_string, symbolize_names: true)
          parse_driver_config(json)
        end

        # Parse driver configuration from a hash
        def parse_driver_config(json)
          DriverConfig.new(
            schema_version: json[:schema_version] || "1.0",
            description: json[:description],
            driver_id: json[:driver_id],
            mode: json[:mode] || "standalone",
            tls: json[:tls],
            auth: json[:auth],
            specific_driver_config: json[:specific_driver_config] || {}
          )
        end

        # Parse workload configuration from a hash
        def parse_workload_config(json)
          phases = (json[:phases] || []).map { |p| parse_phase_config(p) }

          WorkloadConfig.new(
            schema_version: json[:schema_version] || "1.0",
            benchmark_profile: json[:benchmark_profile] || {},
            phases: phases
          )
        end

        private

        def parse_json_file(path)
          content = File.read(path)
          JSON.parse(content, symbolize_names: true)
        end

        def parse_phase_config(json)
          PhaseConfig.new(
            id: json[:id],
            description: json[:description],
            connections: json[:connections],
            cps_limit: json[:cps_limit] || -1,
            rps_limit: json[:rps_limit] || -1,
            pipeline_depth: json[:pipeline_depth] || 1,
            warmup_requests: json[:warmup_requests] || 1,
            completion: parse_completion_config(json[:completion]),
            keyspace: parse_keyspace_config(json[:keyspace]),
            commands: (json[:commands] || []).map { |c| parse_command_config(c) }
          )
        end

        def parse_completion_config(json)
          CompletionConfig.new(
            type: json[:type],
            seconds: json[:seconds],
            requests: json[:requests]
          )
        end

        def parse_keyspace_config(json)
          KeyspaceConfig.new(
            keys_count: json[:keys_count],
            key_size_bytes: json[:key_size_bytes],
            key_prefix: json[:key_prefix],
            generation_alg: json[:generation_alg] || "sequential_int",
            seed: json[:seed]
          )
        end

        def parse_command_config(json)
          CommandConfig.new(
            command: json[:command],
            weight: json[:weight],
            data_size_bytes: json[:data_size_bytes]
          )
        end
      end
    end
  end
end
