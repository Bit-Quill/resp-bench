/**
 * Shared implementation for the ioredis-family drivers.
 *
 * `ioredis` and `iovalkey` (the Valkey-maintained fork) expose the same
 * constructor options and the same `Redis`/`Cluster` exports, so both drivers
 * differ only in which module they load and which package they report a version
 * for. They are described structurally here rather than against either package's
 * types, so neither becomes a compile-time dependency of the other's driver.
 *
 * Fairness note: `enableAutoPipelining` is forced **off**. Left on (it is off by
 * default, but that default has changed before) ioredis transparently batches
 * commands issued in the same event-loop tick, which would inflate throughput
 * against every other engine and silently make the comparison meaningless.
 */

import { readFileSync } from 'node:fs';

import type { DriverConfig } from '../../config/driverConfig.js';
import { measure, type BenchmarkClient } from '../benchmarkClient.js';
import { packageVersion } from '../driverVersion.js';
import type { TimedResult } from '../timedResult.js';

/** The slice of the ioredis surface this engine uses. */
interface RedisLike {
  connect(): Promise<void>;
  ping(): Promise<string>;
  get(key: string): Promise<string | null>;
  set(key: string, value: Buffer): Promise<string | null>;
  quit(): Promise<unknown>;
  disconnect(): void;
}

export interface RedisModuleLike {
  Redis: new (options: Record<string, unknown>) => RedisLike;
  Cluster: new (
    nodes: Array<{ host: string; port: number }>,
    options: Record<string, unknown>,
  ) => RedisLike;
}

function buildTlsOptions(config: DriverConfig): Record<string, unknown> | undefined {
  if (!config.tlsEnabled()) return undefined;
  const tls: Record<string, unknown> = {};
  if (config.tls?.ca_path) tls['ca'] = readFileSync(config.tls.ca_path);
  if (config.tls?.cert_path) tls['cert'] = readFileSync(config.tls.cert_path);
  if (config.tls?.key_path) tls['key'] = readFileSync(config.tls.key_path);
  if (config.tls?.verify_hostname === false) tls['rejectUnauthorized'] = false;
  return tls;
}

export abstract class IoredisFamilyClient implements BenchmarkClient {
  private client: RedisLike | null = null;

  /** npm package name, used for both loading and version reporting. */
  protected abstract packageName(): string;

  protected abstract loadModule(): Promise<RedisModuleLike>;

  async connect(host: string, port: number, config: DriverConfig): Promise<void> {
    const module = await this.loadModule();
    const tls = buildTlsOptions(config);

    const options: Record<string, unknown> = {
      // Batching commands issued in one tick would not be comparable to the
      // other engines -- keep every request a distinct round trip.
      enableAutoPipelining: false,
      // Connect explicitly below so a connection failure surfaces here rather
      // than as a first-command error, and so cps_limit really gates setup.
      lazyConnect: true,
      // Fail a stuck request instead of retrying it under a different latency.
      maxRetriesPerRequest: 0,
      // Never silently reconnect. ioredis' default retryStrategy retries
      // forever, so a wrong host would hang the run instead of failing it, and a
      // mid-phase reconnect would fold connection setup into request latency.
      retryStrategy: () => null,
      // Bound the initial connect so an unreachable host fails fast.
      connectTimeout: config.commandTimeoutMs ?? 10_000,
      ...(config.hasAuth() && config.auth?.username ? { username: config.auth.username } : {}),
      ...(config.hasAuth() && config.auth?.password ? { password: config.auth.password } : {}),
      ...(config.commandTimeoutMs ? { commandTimeout: config.commandTimeoutMs } : {}),
      ...(tls ? { tls } : {}),
    };

    if (config.isCluster()) {
      this.client = new module.Cluster([{ host, port }], {
        lazyConnect: true,
        redisOptions: options,
      });
    } else {
      this.client = new module.Redis({ ...options, host, port });
    }
    await this.client.connect();
  }

  private requireClient(): RedisLike {
    if (this.client === null) throw new Error(`${this.packageName()} client is not connected`);
    return this.client;
  }

  async ping(): Promise<TimedResult<string>> {
    const client = this.requireClient();
    return measure(() => client.ping());
  }

  async get(key: string): Promise<TimedResult<string>> {
    const client = this.requireClient();
    return measure(() => client.get(key)) as Promise<TimedResult<string>>;
  }

  async set(key: string, value: Buffer): Promise<TimedResult<string>> {
    const client = this.requireClient();
    return measure(() => client.set(key, value)) as Promise<TimedResult<string>>;
  }

  async close(): Promise<void> {
    if (this.client === null) return;
    const client = this.client;
    this.client = null;
    try {
      await client.quit();
    } catch {
      // A server that already dropped the connection makes QUIT reject; the
      // socket still has to go, or the process will not exit.
      client.disconnect();
    }
  }

  driverVersion(): string {
    return packageVersion(this.packageName());
  }
}
