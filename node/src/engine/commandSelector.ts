/**
 * Weighted command selection.
 *
 * Uses normalized cumulative weights, matching Java's inline CommandSelector
 * (BenchmarkEngine.java:520-545) and the Python engine. Selection intentionally
 * uses the platform RNG (`Math.random`), not the Java LCG: only *key* generation
 * must be cross-engine deterministic. Each worker owns its own selector, so
 * there is no shared state.
 */

import type { Command } from '../command/command.js';

export class CommandSelector {
  private readonly commands: Command[];
  private readonly cumulativeWeights: number[];

  constructor(commands: Command[]) {
    if (commands.length === 0) throw new Error('CommandSelector requires at least one command');
    this.commands = commands;
    this.cumulativeWeights = CommandSelector.buildCumulativeWeights(commands);
  }

  select(): Command {
    const r = Math.random();
    for (let i = 0; i < this.cumulativeWeights.length; i++) {
      if (r <= this.cumulativeWeights[i]!) return this.commands[i]!;
    }
    return this.commands[this.commands.length - 1]!;
  }

  private static buildCumulativeWeights(commands: Command[]): number[] {
    let totalWeight = commands.reduce((sum, command) => sum + command.weight, 0);
    if (totalWeight === 0) totalWeight = 1.0;

    const cumulative: number[] = [];
    let running = 0;
    for (const command of commands) {
      running += command.weight / totalWeight;
      cumulative.push(running);
    }
    return cumulative;
  }
}
