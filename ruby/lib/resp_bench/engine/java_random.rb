# frozen_string_literal: true

module RespBench
  module Engine
    # Ruby implementation of Java's java.util.Random LCG algorithm.
    # This ensures identical random sequences across Java and Ruby implementations.
    #
    # Java's Random uses a 48-bit Linear Congruential Generator with:
    # - multiplier: 0x5DEECE66D (25214903917)
    # - addend: 0xB (11)
    # - mask: (1 << 48) - 1
    #
    # @see https://docs.oracle.com/javase/8/docs/api/java/util/Random.html
    class JavaRandom
      MULTIPLIER = 0x5DEECE66D
      ADDEND = 0xB
      MASK = (1 << 48) - 1

      # Initialize with a seed value (same behavior as Java's Random(long seed))
      # @param seed [Integer] the seed value
      def initialize(seed)
        @seed = initial_scramble(seed)
      end

      # Generate the next random integer in range [0, bound)
      # Matches Java's Random.nextInt(int bound) behavior
      #
      # @param bound [Integer] the upper bound (exclusive)
      # @return [Integer] random integer in [0, bound)
      def next_int(bound)
        raise ArgumentError, "bound must be positive" if bound <= 0

        # Special case for powers of 2
        if (bound & -bound) == bound # i.e., bound is a power of 2
          return ((bound * next_bits(31)) >> 31)
        end

        # General case - rejection sampling to avoid modulo bias
        loop do
          bits = next_bits(31)
          val = bits % bound
          return val if bits - val + (bound - 1) >= 0
        end
      end

      # Reset the generator with a new seed
      # @param seed [Integer] the new seed value
      def set_seed(seed)
        @seed = initial_scramble(seed)
      end

      private

      # Initial scramble of the seed (matches Java's (seed ^ multiplier) & mask)
      def initial_scramble(seed)
        (seed ^ MULTIPLIER) & MASK
      end

      # Generate the next random bits
      # @param bits [Integer] number of bits to generate (1-32)
      # @return [Integer] random bits
      def next_bits(bits)
        @seed = ((@seed * MULTIPLIER) + ADDEND) & MASK
        (@seed >> (48 - bits))
      end
    end
  end
end
