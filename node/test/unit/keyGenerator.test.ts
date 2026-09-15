import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { KeyspaceConfig } from '../../src/config/keyspaceConfig.js';
import { Counter, KeyGenerator } from '../../src/engine/keyGenerator.js';

describe('KeyGenerator', () => {
  describe('sequential_int', () => {
    it('emits 0..N-1 then wraps', () => {
      const gen = KeyGenerator.create(
        new KeyspaceConfig({ keysCount: 3, keyPrefix: 'test:', keySizeBytes: 6 }),
      );
      assert.deepEqual(
        Array.from({ length: 4 }, () => gen.nextKey()),
        ['test:0', 'test:1', 'test:2', 'test:0'],
      );
    });

    it('shares one counter across workers, as Java does', () => {
      // The whole point of the shared counter: several connections collectively
      // populate the keyspace instead of each replaying 0, 1, 2, ...
      const config = new KeyspaceConfig({ keysCount: 100, keyPrefix: 'k:', keySizeBytes: 4 });
      const counter = new Counter();
      const workerA = KeyGenerator.createWithSeed(config, 0, counter);
      const workerB = KeyGenerator.createWithSeed(config, 1, counter);
      assert.deepEqual(
        [workerA.nextKey(), workerB.nextKey(), workerA.nextKey(), workerB.nextKey()],
        ['k:00', 'k:01', 'k:02', 'k:03'],
      );
    });

    it('gives each generator its own counter when none is shared', () => {
      const config = new KeyspaceConfig({ keysCount: 100, keyPrefix: 'k:', keySizeBytes: 4 });
      assert.equal(KeyGenerator.create(config).nextKey(), 'k:00');
      assert.equal(KeyGenerator.create(config).nextKey(), 'k:00');
    });
  });

  describe('uniform_rand', () => {
    it('is reproducible for the same seed', () => {
      const config = new KeyspaceConfig({
        keysCount: 1000,
        keyPrefix: 'test:',
        generationAlg: 'uniform_rand',
        seed: 12345,
      });
      const a = KeyGenerator.create(config);
      const b = KeyGenerator.create(config);
      for (let i = 0; i < 100; i++) assert.equal(a.nextKey(), b.nextKey());
    });

    it('derives a per-worker seed of base + index, as Java does', () => {
      const config = new KeyspaceConfig({
        keysCount: 1000,
        keyPrefix: 'test:',
        generationAlg: 'uniform_rand',
        seed: 12345,
      });
      const worker0 = KeyGenerator.createWithSeed(config, 12345);
      const worker1 = KeyGenerator.createWithSeed(config, 12346);
      assert.notEqual(worker0.nextKey(), worker1.nextKey());
    });

    it('matches the JavaRandom sequence for seed 12345', () => {
      // Anchored to the same values as javaRandom.test.ts, so a key-formatting
      // change cannot silently break cross-engine key parity.
      const gen = KeyGenerator.create(
        new KeyspaceConfig({
          keysCount: 1000,
          keyPrefix: 'bench:',
          keySizeBytes: 16,
          generationAlg: 'uniform_rand',
          seed: 12345,
        }),
      );
      assert.deepEqual(
        Array.from({ length: 4 }, () => gen.nextKey()),
        ['bench:0000000251', 'bench:0000000080', 'bench:0000000241', 'bench:0000000828'],
      );
    });

    it('reset restores the stream', () => {
      const gen = KeyGenerator.create(
        new KeyspaceConfig({
          keysCount: 1000,
          keyPrefix: 'test:',
          generationAlg: 'uniform_rand',
          seed: 999,
        }),
      );
      const first = Array.from({ length: 5 }, () => gen.nextKey());
      gen.reset();
      assert.deepEqual(
        Array.from({ length: 5 }, () => gen.nextKey()),
        first,
      );
    });
  });

  describe('key formatting', () => {
    it('zero-pads to key_size_bytes minus the prefix, as Java does', () => {
      // Reference configs use key_prefix "bench:" (6) and key_size_bytes 16,
      // so the numeric part is 10 digits: bench:0000000042
      const gen = KeyGenerator.create(
        new KeyspaceConfig({ keysCount: 1_000_000, keyPrefix: 'bench:', keySizeBytes: 16 }),
      );
      const key = gen.nextKey();
      assert.equal(key, 'bench:0000000000');
      assert.equal(key.length, 16);
    });

    it('keeps at least one digit when the prefix fills key_size_bytes', () => {
      const gen = KeyGenerator.create(
        new KeyspaceConfig({ keysCount: 10, keyPrefix: 'averylongprefix:', keySizeBytes: 4 }),
      );
      assert.equal(gen.nextKey(), 'averylongprefix:0');
    });

    it('does not truncate an index wider than the padding', () => {
      const gen = KeyGenerator.create(
        new KeyspaceConfig({ keysCount: 1000, keyPrefix: 'k:', keySizeBytes: 4 }),
      );
      const keys = Array.from({ length: 101 }, () => gen.nextKey());
      assert.equal(keys[0], 'k:00');
      assert.equal(keys[100], 'k:100');
    });

    it('applies the documented defaults', () => {
      const gen = KeyGenerator.create(new KeyspaceConfig({ keysCount: 10 }));
      assert.equal(gen.nextKey(), 'bench:0000000000');
    });
  });
});
