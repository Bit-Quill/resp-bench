# frozen_string_literal: true

module RespBench
  module Engine
    # Selects commands based on their configured weights.
    # Uses cumulative weights for efficient O(log n) selection.
    class CommandSelector
      # @param commands [Array<RespBench::Command::Command>] list of commands with weights
      def initialize(commands)
        @commands = commands
        @cumulative_weights = build_cumulative_weights(commands)
        @random = Random.new # Ruby's built-in random is fine for selection
      end

      # Select a command based on configured weights
      # @return [RespBench::Command::Command] selected command
      def select
        r = @random.rand
        @cumulative_weights.each_with_index do |threshold, index|
          return @commands[index] if r <= threshold
        end
        @commands.last
      end

      private

      def build_cumulative_weights(commands)
        # Normalize weights to sum to 1.0
        total_weight = commands.sum(&:weight)
        total_weight = 1.0 if total_weight.zero?

        cumulative = []
        sum = 0.0
        commands.each do |cmd|
          sum += cmd.weight / total_weight
          cumulative << sum
        end
        cumulative
      end
    end
  end
end
