import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { RateLimiter } from '../../src/engine/rateLimiter.js';

describe('RateLimiter', () => {
  it('returns null for an unlimited rate', () => {
    assert.equal(RateLimiter.create(0), null);
    assert.equal(RateLimiter.create(-1), null);
  });

  it('returns a limiter for a positive rate', () => {
    const limiter = RateLimiter.create(100);
    assert.ok(limiter);
    assert.equal(limiter.ratePerSecond, 100);
  });

  it('achieves the target rate within 5%', async () => {
    // docs/ADDING_LANGUAGE.md's stated tolerance for the rate limiter.
    const rate = 200;
    const requests = 100;
    const limiter = RateLimiter.create(rate)!;

    const start = process.hrtime.bigint();
    for (let i = 0; i < requests; i++) await limiter.acquire();
    const elapsedSeconds = Number(process.hrtime.bigint() - start) / 1e9;

    // The first acquire is free (the bucket starts open), so the limiter only
    // paces the remaining requests.
    const expectedSeconds = (requests - 1) / rate;
    assert.ok(
      elapsedSeconds >= expectedSeconds * 0.95,
      `finished too fast: ${elapsedSeconds.toFixed(3)}s < ${(expectedSeconds * 0.95).toFixed(3)}s`,
    );
    assert.ok(
      elapsedSeconds <= expectedSeconds * 1.3,
      `finished too slow: ${elapsedSeconds.toFixed(3)}s > ${(expectedSeconds * 1.3).toFixed(3)}s`,
    );
  });

  it('paces a rate whose interval is below setTimeout resolution', async () => {
    // 5000/s is a 200us interval -- well under setTimeout's ~1ms floor, so this
    // only passes if sub-millisecond waits yield via setImmediate instead.
    const rate = 5000;
    const requests = 500;
    const limiter = RateLimiter.create(rate)!;

    const start = process.hrtime.bigint();
    for (let i = 0; i < requests; i++) await limiter.acquire();
    const elapsedSeconds = Number(process.hrtime.bigint() - start) / 1e9;

    const expectedSeconds = (requests - 1) / rate;
    assert.ok(
      elapsedSeconds <= expectedSeconds * 2,
      `sub-ms pacing overshot badly: ${elapsedSeconds.toFixed(3)}s vs ${expectedSeconds.toFixed(3)}s ` +
        '(a clamped setTimeout would take ~10x this)',
    );
  });

  it('shares one budget across concurrent callers', async () => {
    const rate = 200;
    const perWorker = 25;
    const workers = 4;
    const limiter = RateLimiter.create(rate)!;

    const start = process.hrtime.bigint();
    await Promise.all(
      Array.from({ length: workers }, async () => {
        for (let i = 0; i < perWorker; i++) await limiter.acquire();
      }),
    );
    const elapsedSeconds = Number(process.hrtime.bigint() - start) / 1e9;

    // The limit is global: 100 requests at 200/s takes ~0.5s regardless of how
    // many workers issue them.
    const expectedSeconds = (workers * perWorker - 1) / rate;
    assert.ok(
      elapsedSeconds >= expectedSeconds * 0.95,
      `concurrent callers bypassed the limit: ${elapsedSeconds.toFixed(3)}s`,
    );
  });
});
