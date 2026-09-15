/** A benchmark command: a weighted operation the engine can issue. */

import type { BenchmarkClient } from '../client/benchmarkClient.js';

export interface CommandResult {
  readonly commandName: string;
  readonly latencyMicros: number;
  readonly success: boolean;
  readonly errorMessage?: string;
}

export interface Command {
  /** Upper-case command name, used as the NDJSON metrics key (GET/SET/PING). */
  readonly name: string;
  readonly weight: number;
  /** Whether this command consumes a generated key (PING does not). */
  readonly usesKey: boolean;
  execute(client: BenchmarkClient, key: string): Promise<CommandResult>;
}
