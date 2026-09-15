import type { BenchmarkClient } from '../../client/benchmarkClient.js';
import type { CommandConfig } from '../../config/commandConfig.js';
import type { Command, CommandResult } from '../command.js';

/**
 * Deterministic filler pattern, matching the Ruby and Python engines. Java uses
 * random bytes instead, but only the payload *length* affects RESP framing and
 * server work, and a fixed pattern makes runs reproducible.
 */
const PATTERN = '0123456789ABCDEF';

export class SetCommand implements Command {
  readonly name = 'SET';
  readonly weight: number;
  readonly usesKey = true;
  /**
   * Built once at construction, not per request: allocating a fresh payload in
   * the hot loop would charge the driver for the engine's own GC churn.
   */
  private readonly value: Buffer;

  constructor(config: CommandConfig) {
    this.weight = config.weight;
    this.value = SetCommand.generateValue(config.dataSizeBytes);
  }

  async execute(client: BenchmarkClient, key: string): Promise<CommandResult> {
    const result = await client.set(key, this.value);
    return {
      commandName: this.name,
      latencyMicros: result.latencyMicros,
      success: result.error === undefined,
      ...(result.error ? { errorMessage: result.error.message } : {}),
    };
  }

  static generateValue(size: number): Buffer {
    const repeats = Math.floor(size / PATTERN.length) + 1;
    return Buffer.from(PATTERN.repeat(repeats).slice(0, size), 'latin1');
  }
}
