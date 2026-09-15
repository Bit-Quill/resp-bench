/**
 * JavaRandom parity tests.
 *
 * The LCG is anchored to a well-known java.util.Random value, which proves
 * byte-for-byte compatibility with the Java reference without needing a JVM. The
 * two sequences below were additionally cross-checked against the Python
 * engine's JavaRandom, so a failure here means Node has diverged from both.
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { JavaRandom, toInt32 } from '../../src/engine/javaRandom.js';

describe('JavaRandom', () => {
  it('matches the documented java.util.Random(0).nextInt() value', () => {
    // java.util.Random(0).nextInt() -- i.e. next(32) as a signed int -- is the
    // well-documented value -1155484576. This anchors the LCG to real Java.
    assert.equal(toInt32(new JavaRandom(0).next(32)), -1155484576);
  });

  it('reproduces the cross-engine sequence for seed 12345, bound 1000', () => {
    const rng = new JavaRandom(12345);
    const actual = Array.from({ length: 10 }, () => rng.nextInt(1000));
    assert.deepEqual(actual, [251, 80, 241, 828, 55, 84, 375, 802, 501, 389]);
  });

  it('reproduces the cross-engine sequence for a power-of-two bound', () => {
    // Exercises the power-of-two fast path, a separate branch in Java.
    const rng = new JavaRandom(12345);
    const actual = Array.from({ length: 8 }, () => rng.nextInt(256));
    assert.deepEqual(actual, [92, 131, 238, 234, 213, 9, 83, 31]);
  });

  it('is deterministic for a given seed', () => {
    const rngA = new JavaRandom(12345);
    const rngB = new JavaRandom(12345);
    assert.deepEqual(
      Array.from({ length: 10 }, () => rngA.nextInt(1000)),
      Array.from({ length: 10 }, () => rngB.nextInt(1000)),
    );
  });

  it('produces different sequences for different seeds', () => {
    const a = new JavaRandom(12345);
    const b = new JavaRandom(54321);
    assert.notDeepEqual(
      Array.from({ length: 10 }, () => a.nextInt(1000)),
      Array.from({ length: 10 }, () => b.nextInt(1000)),
    );
  });

  it('setSeed resets the stream', () => {
    const rng = new JavaRandom(12345);
    const first = Array.from({ length: 5 }, () => rng.nextInt(1000));
    rng.setSeed(12345);
    const second = Array.from({ length: 5 }, () => rng.nextInt(1000));
    assert.deepEqual(first, second);
  });

  it('rejects a non-positive bound', () => {
    const rng = new JavaRandom(12345);
    assert.throws(() => rng.nextInt(0), /bound must be a positive integer/);
    assert.throws(() => rng.nextInt(-1), /bound must be a positive integer/);
  });

  it('stays within the bound', () => {
    const rng = new JavaRandom(12345);
    for (let i = 0; i < 1000; i++) {
      const value = rng.nextInt(100);
      assert.ok(value >= 0 && value < 100, `${value} out of range`);
    }
  });

  it('stays within power-of-two bounds', () => {
    const rng = new JavaRandom(12345);
    for (const bound of [2, 4, 8, 16, 256, 1024]) {
      for (let i = 0; i < 200; i++) {
        const value = rng.nextInt(bound);
        assert.ok(value >= 0 && value < bound, `${value} out of range for ${bound}`);
      }
    }
  });
});
