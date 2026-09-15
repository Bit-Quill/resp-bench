/**
 * Driver registry: maps `driver_id` to a client implementation.
 *
 * Implementations are loaded with a dynamic `import()` so `--info` and the unit
 * tests work even if a driver's native bits are missing or broken on this
 * platform -- only the driver actually requested gets loaded.
 *
 * The ids matter beyond this file. `DRIVER_ENGINE_MAP` in
 * scripts/run_benchmark_matrix.py is a single global map shared by every engine,
 * and `valkey-glide` there already means *Java*. Hence `valkey-glide-node`: a
 * bare `valkey-glide` here would silently reroute Java's glide runs to Node.
 */

import type { DriverConfig } from '../config/driverConfig.js';
import type { BenchmarkClient } from './benchmarkClient.js';

interface DriverEntry {
  description: string;
  load: () => Promise<BenchmarkClient>;
}

const DRIVERS = new Map<string, DriverEntry>([
  [
    'valkey-glide-node',
    {
      description: 'Valkey GLIDE for Node.js (@valkey/valkey-glide)',
      load: async () => new (await import('./impl/glideClient.js')).GlideBenchmarkClient(),
    },
  ],
  [
    'ioredis',
    {
      description: 'ioredis — the most widely used Node.js Redis client',
      load: async () => new (await import('./impl/ioredisClient.js')).IoredisBenchmarkClient(),
    },
  ],
  [
    'iovalkey',
    {
      description: 'iovalkey — the Valkey-maintained fork of ioredis',
      load: async () => new (await import('./impl/iovalkeyClient.js')).IovalkeyBenchmarkClient(),
    },
  ],
  [
    'recording',
    {
      description: 'In-memory synthetic-latency client (no server required)',
      load: async () => new (await import('./impl/recordingClient.js')).RecordingClient(),
    },
  ],
]);

export class BenchmarkClientFactory {
  static supportedDrivers(): string[] {
    return [...DRIVERS.keys()];
  }

  static describe(): Array<{ driverId: string; description: string }> {
    return [...DRIVERS.entries()].map(([driverId, { description }]) => ({ driverId, description }));
  }

  static async create(driverId: string): Promise<BenchmarkClient> {
    const entry = DRIVERS.get((driverId ?? '').toLowerCase());
    if (entry === undefined) {
      throw new Error(
        `Unknown driver: ${driverId}. Supported: ${BenchmarkClientFactory.supportedDrivers().join(', ')}`,
      );
    }
    return entry.load();
  }

  static async createAndConnect(
    host: string,
    port: number,
    config: DriverConfig,
  ): Promise<BenchmarkClient> {
    const client = await BenchmarkClientFactory.create(config.driverId);
    await client.connect(host, port, config);
    return client;
  }
}
