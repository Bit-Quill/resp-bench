/**
 * iovalkey driver — the Valkey-maintained fork of ioredis.
 *
 * API-identical to ioredis, so all behaviour lives in the shared base class.
 */

import { IoredisFamilyClient, type RedisModuleLike } from './ioredisFamilyClient.js';

export class IovalkeyBenchmarkClient extends IoredisFamilyClient {
  protected override packageName(): string {
    return 'iovalkey';
  }

  protected override async loadModule(): Promise<RedisModuleLike> {
    const module = await import('iovalkey');
    // Same overload-narrowing as the ioredis driver; iovalkey mirrors its API.
    return { Redis: module.Redis, Cluster: module.Cluster } as unknown as RedisModuleLike;
  }
}
