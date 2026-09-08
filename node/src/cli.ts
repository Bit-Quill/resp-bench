/**
 * Command-line interface for the resp-bench Node.js engine.
 *
 * Implements the shared cross-engine CLI contract: `--server`, `--driver`,
 * `--workload`, `--metrics`, plus `--info`, `--commit-id` (used by CI), and
 * `--version`. Deliberately no `--concurrency` flag: the engine has a single
 * execution model, and pipelining comes from the workload's `pipeline_depth`.
 */

import { existsSync } from 'node:fs';
import { parseArgs } from 'node:util';

import { BenchmarkClientFactory } from './client/factory.js';
import { CommandFactory } from './command/factory.js';
import { ConfigLoader } from './config/loader.js';
import { BenchmarkEngine } from './engine/benchmark.js';
import { VERSION } from './version.js';

const DEFAULT_SERVER = 'localhost:6379';
const DEFAULT_PORT = 6379;

const USAGE = `resp-bench Node.js engine v${VERSION}

Usage:
  node dist/src/cli.js --driver <path> --workload <path> --metrics <path> [options]

Options:
  --server <host:port>   Server address (default: ${DEFAULT_SERVER})
  --driver <path>        Driver configuration JSON (required)
  --workload <path>      Workload configuration JSON (required)
  --metrics <path>       Metrics NDJSON output path (required)
  --commit-id <sha>      Git commit ID recorded in the metrics metadata
  --info                 Show supported drivers and commands
  --version              Show the engine version
  --help                 Show this message
`;

function parseServer(server: string): { host: string; port: number } {
  const separator = server.lastIndexOf(':');
  if (separator === -1) return { host: server || 'localhost', port: DEFAULT_PORT };
  const host = server.slice(0, separator) || 'localhost';
  const port = Number(server.slice(separator + 1));
  if (!Number.isInteger(port) || port <= 0) {
    throw new Error(`invalid --server "${server}": port must be a positive integer`);
  }
  return { host, port };
}

function printInfo(): void {
  const lines = [
    `resp-bench Node.js Engine v${VERSION}`,
    '',
    'Supported Drivers:',
    ...BenchmarkClientFactory.describe().map(
      ({ driverId, description }) => `  - ${driverId.padEnd(20)} : ${description}`,
    ),
    '',
    'Supported Commands:',
    ...CommandFactory.describe().map(({ name, description }) => `  - ${name.padEnd(10)} : ${description}`),
    '',
    'Supported Key Generation Algorithms:',
    '  - sequential_int : Sequential integers (0 to keys_count), shared across connections',
    '  - uniform_rand   : Uniform random, java.util.Random-compatible per connection',
    '',
    'Supported Completion Types:',
    '  - duration : Run for the specified seconds',
    '  - requests : Run until the shared request budget is exhausted',
    '',
    'Concurrency: event-loop task-per-connection (one client per connection)',
  ];
  console.log(lines.join('\n'));
}

export async function main(argv: string[] = process.argv.slice(2)): Promise<number> {
  let options;
  try {
    ({ values: options } = parseArgs({
      args: argv,
      options: {
        server: { type: 'string', default: DEFAULT_SERVER },
        driver: { type: 'string' },
        workload: { type: 'string' },
        metrics: { type: 'string' },
        'commit-id': { type: 'string' },
        info: { type: 'boolean', default: false },
        version: { type: 'boolean', default: false },
        help: { type: 'boolean', default: false },
      },
      strict: true,
    }));
  } catch (error) {
    console.error(`Error: ${(error as Error).message}`);
    console.error(USAGE);
    return 1;
  }

  if (options.help) {
    console.log(USAGE);
    return 0;
  }
  if (options.version) {
    console.log(`resp-bench Node.js Engine v${VERSION}`);
    return 0;
  }
  if (options.info) {
    printInfo();
    return 0;
  }

  try {
    const missing = (['driver', 'workload', 'metrics'] as const).filter((flag) => !options[flag]);
    if (missing.length > 0) {
      throw new Error(`missing required options: ${missing.map((f) => `--${f}`).join(', ')}`);
    }
    for (const flag of ['driver', 'workload'] as const) {
      if (!existsSync(options[flag]!)) {
        throw new Error(`${flag} config not found: ${options[flag]}`);
      }
    }

    const { host, port } = parseServer(options.server!);
    const engine = new BenchmarkEngine({
      host,
      port,
      driverConfig: ConfigLoader.loadDriverConfig(options.driver!),
      workloadConfig: ConfigLoader.loadWorkloadConfig(options.workload!),
      metricsPath: options.metrics!,
      commitId: options['commit-id'] ?? null,
    });
    await engine.run();
    return 0;
  } catch (error) {
    console.error(`Error: ${(error as Error).message}`);
    if (process.env['DEBUG']) console.error((error as Error).stack);
    return 1;
  }
}

// `import.meta.url` check keeps the module importable by tests without running.
if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  process.exitCode = await main();
}
