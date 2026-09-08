import type { BenchmarkClient } from '../../client/benchmarkClient.js';
import type { CommandConfig } from '../../config/commandConfig.js';
import type { Command, CommandResult } from '../command.js';

export class GetCommand implements Command {
  readonly name = 'GET';
  readonly weight: number;
  readonly usesKey = true;

  constructor(config: CommandConfig) {
    this.weight = config.weight;
  }

  async execute(client: BenchmarkClient, key: string): Promise<CommandResult> {
    const result = await client.get(key);
    return {
      commandName: this.name,
      latencyMicros: result.latencyMicros,
      success: result.error === undefined,
      ...(result.error ? { errorMessage: result.error.message } : {}),
    };
  }
}
