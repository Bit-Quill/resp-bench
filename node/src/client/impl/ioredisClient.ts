/** ioredis driver — the most widely used Node.js Redis client. */

import { IoredisFamilyClient, type RedisModuleLike } from './ioredisFamilyClient.js';

export class IoredisBenchmarkClient extends IoredisFamilyClient {
  protected override packageName(): string {
    return 'ioredis';
  }

  protected override async loadModule(): Promise<RedisModuleLike> {
    const module = await import('ioredis');
    // ioredis' constructors carry overloads that RedisModuleLike narrows to the
    // one form this engine calls; the shapes are compatible at runtime.
    return { Redis: module.Redis, Cluster: module.Cluster } as unknown as RedisModuleLike;
  }
}
