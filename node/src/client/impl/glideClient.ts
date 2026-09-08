/**
 * valkey-glide driver (`@valkey/valkey-glide`).
 *
 * One GlideClient per connection, honouring the `client == connection` invariant
 * shared across engines.
 *
 * Two glide-specific shapes to note:
 * - `close()` is **synchronous** (returns void, not a Promise), unlike every
 *   other driver here.
 * - `get()` returns a `string` by default. That is deliberate and matches the
 *   Python engine and the ioredis/iovalkey clients here, so all drivers are
 *   charged for the same UTF-8 decode. Do not switch one driver to bytes.
 */

import type {
  GlideClient,
  GlideClientConfiguration,
  GlideClusterClient,
  GlideClusterClientConfiguration,
  ServerCredentials,
} from '@valkey/valkey-glide';

import type { DriverConfig } from '../../config/driverConfig.js';
import { measure, type BenchmarkClient } from '../benchmarkClient.js';
import { packageVersion } from '../driverVersion.js';
import type { TimedResult } from '../timedResult.js';

const PACKAGE = '@valkey/valkey-glide';

export class GlideBenchmarkClient implements BenchmarkClient {
  private client: GlideClient | GlideClusterClient | null = null;

  async connect(host: string, port: number, config: DriverConfig): Promise<void> {
    const glide = await import('@valkey/valkey-glide');

    const addresses = [{ host, port }];
    const credentials: ServerCredentials | undefined = config.hasAuth()
      ? ({
          password: config.auth?.password ?? '',
          ...(config.auth?.username ? { username: config.auth.username } : {}),
        } as ServerCredentials)
      : undefined;

    const shared = {
      addresses,
      useTLS: config.tlsEnabled(),
      ...(credentials ? { credentials } : {}),
      ...(config.commandTimeoutMs ? { requestTimeout: config.commandTimeoutMs } : {}),
    };

    this.client = config.isCluster()
      ? await glide.GlideClusterClient.createClient(shared as GlideClusterClientConfiguration)
      : await glide.GlideClient.createClient(shared as GlideClientConfiguration);
  }

  private requireClient(): GlideClient | GlideClusterClient {
    if (this.client === null) throw new Error('glide client is not connected');
    return this.client;
  }

  async ping(): Promise<TimedResult<string>> {
    const client = this.requireClient();
    return measure(async () => String(await client.ping()));
  }

  async get(key: string): Promise<TimedResult<string>> {
    const client = this.requireClient();
    return measure(async () => {
      const value = await client.get(key);
      return value === null ? null : String(value);
    }) as Promise<TimedResult<string>>;
  }

  async set(key: string, value: Buffer): Promise<TimedResult<string>> {
    const client = this.requireClient();
    return measure(async () => String(await client.set(key, value)));
  }

  async close(): Promise<void> {
    // Synchronous in glide -- there is nothing to await.
    this.client?.close();
    this.client = null;
  }

  driverVersion(): string {
    return packageVersion(PACKAGE);
  }
}
