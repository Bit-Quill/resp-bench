# frozen_string_literal: true

module RespBench
  module Config
    # Configuration for key generation in a benchmark phase.
    class KeyspaceConfig
      DEFAULT_KEY_SIZE_BYTES = 16
      DEFAULT_KEY_PREFIX = "bench:"

      attr_accessor :keys_count, :key_size_bytes, :key_prefix, :generation_alg, :seed

      def initialize(
        keys_count:,
        key_size_bytes: DEFAULT_KEY_SIZE_BYTES,
        key_prefix: DEFAULT_KEY_PREFIX,
        generation_alg: "sequential_int",
        seed: nil
      )
        @keys_count = keys_count
        @key_size_bytes = key_size_bytes || DEFAULT_KEY_SIZE_BYTES
        @key_prefix = key_prefix || DEFAULT_KEY_PREFIX
        @generation_alg = generation_alg
        @seed = seed
      end

      def sequential_int?
        @generation_alg == "sequential_int"
      end

      def uniform_rand?
        @generation_alg == "uniform_rand"
      end

      # Returns the effective key prefix (never nil)
      def effective_key_prefix
        @key_prefix || DEFAULT_KEY_PREFIX
      end

      # Returns the seed value (defaults to 0 if not set)
      def seed_value
        @seed || 0
      end

      def to_h
        {
          keys_count: @keys_count,
          key_size_bytes: @key_size_bytes,
          key_prefix: @key_prefix,
          generation_alg: @generation_alg,
          seed: @seed
        }
      end
    end
  end
end
