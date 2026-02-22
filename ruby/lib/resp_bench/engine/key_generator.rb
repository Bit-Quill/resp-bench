# frozen_string_literal: true

require "concurrent"

module RespBench
  module Engine
    # Key generator for benchmark operations.
    # Produces identical key sequences as the Java reference implementation.
    #
    # Supports two generation algorithms:
    # - sequential_int: Keys 0, 1, 2, ... N-1 (wraps around)
    # - uniform_rand: Random keys using Java-compatible LCG
    class KeyGenerator
      # @param config [RespBench::Config::KeyspaceConfig] keyspace configuration
      # @param seed_override [Integer, nil] optional seed override for thread-local instances
      def initialize(config, seed_override: nil)
        @config = config
        @key_prefix = config.effective_key_prefix
        @key_size_bytes = config.key_size_bytes
        @keys_count = config.keys_count
        @seed = seed_override || config.seed_value
        @sequential_counter = Concurrent::AtomicFixnum.new(0)
        @random = JavaRandom.new(@seed)
        @mutex = Mutex.new # For thread-safe random access
      end

      # Generate the next key as a byte string
      # @return [String] the generated key (binary string)
      def next_key
        key_index = if @config.sequential_int?
                      @sequential_counter.increment - 1
                    else
                      @mutex.synchronize { @random.next_int(@keys_count) }
                    end

        key_index = key_index % @keys_count
        format_key(key_index)
      end

      # Reset the generator to initial state
      def reset
        @sequential_counter.value = 0
        @mutex.synchronize do
          @random.set_seed(@seed)
        end
      end

      # Factory method
      # @param config [RespBench::Config::KeyspaceConfig] keyspace configuration
      # @return [KeyGenerator] new key generator instance
      def self.create(config)
        new(config)
      end

      # Factory method with custom seed override.
      # Used to create thread-local key generators with unique but reproducible seeds.
      # @param config [RespBench::Config::KeyspaceConfig] keyspace configuration
      # @param seed [Integer] seed value to use instead of config's seed
      # @return [KeyGenerator] new key generator instance with overridden seed
      def self.create_with_seed(config, seed)
        new(config, seed_override: seed)
      end

      private

      # Format a key index into a full key string
      # Matches Java's String.format("%0Nd", keyIndex) behavior
      #
      # @param key_index [Integer] the key index
      # @return [String] formatted key string
      def format_key(key_index)
        # Calculate padding width: key_size_bytes - prefix length, minimum 1
        padding_width = [@key_size_bytes - @key_prefix.length, 1].max
        format_string = "%0#{padding_width}d"
        "#{@key_prefix}#{format_string % key_index}"
      end
    end
  end
end
