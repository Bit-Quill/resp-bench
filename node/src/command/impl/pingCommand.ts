import type { BenchmarkClient } from '../../client/benchmarkClient.js';
import type { CommandConfig } from '../../config/commandConfig.js';
import type { Command, CommandResult } from '../command.js';

export class PingCommand implements Command {
  readonly name = 'PING';
  readonly weight: number;
  readonly usesKey = false;

  constructor(config: CommandConfig) {
    this.weight = config.weight;
  }

  async execute(client: BenchmarkClient): Promise<CommandResult> {
    const result = await client.ping();
    return {
      commandName: this.name,
      latencyMicros: result.latencyMicros,
      success: result.error === undefined,
      ...(result.error ? { errorMessage: result.error.message } : {}),
    };
  }
}
