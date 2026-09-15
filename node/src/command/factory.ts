/** Maps a workload's `command` strings to Command implementations. */

import type { CommandConfig } from '../config/commandConfig.js';
import type { Command } from './command.js';
import { GetCommand } from './impl/getCommand.js';
import { PingCommand } from './impl/pingCommand.js';
import { SetCommand } from './impl/setCommand.js';

type CommandBuilder = (config: CommandConfig) => Command;

const BUILDERS = new Map<string, { build: CommandBuilder; description: string }>([
  ['get', { build: (c) => new GetCommand(c), description: 'GET key' }],
  ['set', { build: (c) => new SetCommand(c), description: 'SET key value' }],
  ['ping', { build: (c) => new PingCommand(c), description: 'PING' }],
]);

export class CommandFactory {
  static supportedCommands(): string[] {
    return [...BUILDERS.keys()];
  }

  static describe(): Array<{ name: string; description: string }> {
    return [...BUILDERS.entries()].map(([name, { description }]) => ({ name, description }));
  }

  static create(config: CommandConfig): Command {
    const entry = BUILDERS.get(config.command.toLowerCase());
    if (entry === undefined) {
      throw new Error(
        `Unknown command: ${config.command}. Supported: ${CommandFactory.supportedCommands().join(', ')}`,
      );
    }
    return entry.build(config);
  }

  static createAll(configs: CommandConfig[]): Command[] {
    return configs.map((config) => CommandFactory.create(config));
  }
}
