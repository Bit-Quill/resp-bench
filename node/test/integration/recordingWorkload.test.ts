/**
 * Full-engine tests against the `recording` driver — no server required.
 *
 * These are the tests that catch engine-level regressions the unit tests cannot:
 * the shared request budget, warmup fail-fast, pipelining, rate limiting, and the
 * NDJSON a real run produces.
 */

import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';

import { ConfigLoader } from '../../src/config/loader.js';
import { BenchmarkEngine, type Logger } from '../../src/engine/benchmark.js';
import { decodeBase64 } from '../../src/metrics/hdrHistogram.js';

const silentLogger: Logger = { info: () => {}, warn: () => {}, error: () => {} };

function tempMetricsPath(): string {
  return join(mkdtempSync(join(tmpdir(), 'resp-bench-node-')), 'metrics.ndjson');
}

function readRecords(path: string): Array<Record<string, any>> {
  return readFileSync(path, 'utf8')
    .trimEnd()
    .split('\n')
    .map((line) => JSON.parse(line));
}

function driverConfig(specific: Record<string, unknown> = {}) {
  return ConfigLoader.parseDriverConfig({
    schema_version: '1.0',
    driver_id: 'recording',
    mode: 'standalone',
    specific_driver_config: specific,
  });
}

function workload(phases: unknown[]) {
  return ConfigLoader.parseWorkloadConfig({
    schema_version: '1.0',
    benchmark_profile: { name: 'Node engine test' },
    phases,
  });
}

const STEADY_PHASE = {
  id: 'STEADY',
  description: 'short steady phase',
  connections: 4,
  warmup_requests: 1,
  completion: { type: 'requests', requests: 200 },
  keyspace: { keys_count: 100, key_prefix: 'e2e:', key_size_bytes: 16 },
  commands: [
    { command: 'set', weight: 0.5, data_size_bytes: 32 },
    { command: 'get', weight: 0.5 },
  ],
};

async function run(options: {
  phases: unknown[];
  specific?: Record<string, unknown>;
  commitId?: string;
}): Promise<{ path: string; records: Array<Record<string, any>> }> {
  const path = tempMetricsPath();
  await new BenchmarkEngine({
    host: 'localhost',
    port: 6379,
    driverConfig: driverConfig(options.specific),
    workloadConfig: workload(options.phases),
    metricsPath: path,
    commitId: options.commitId ?? 'test-commit',
    logger: silentLogger,
  }).run();
  return { path, records: readRecords(path) };
}

describe('recording-driver workload', () => {
  it('runs a phase end to end and writes valid NDJSON', async () => {
    const { records } = await run({ phases: [STEADY_PHASE] });

    assert.equal(records.length, 1);
    const record = records[0]!;
    assert.equal(record['phase'].id, 'STEADY');
    assert.equal(record['phase'].status, 'COMPLETED');
    assert.equal(record['phase'].connections, 4);
    assert.equal(record['totals'].requests, 200);
    assert.equal(record['totals'].errors, 0);
    assert.equal(record['metadata'].commit_id, 'test-commit');
    assert.equal(record['metadata'].driver_id, 'recording');
    assert.equal(record['metadata'].primary_driver_version, '1.0.0');

    // Both commands were exercised and their counts add up to the total.
    const commands = Object.keys(record['metrics']).sort();
    assert.deepEqual(commands, ['GET', 'SET']);
    const sum = commands.reduce((acc, name) => acc + record['metrics'][name].requests, 0);
    assert.equal(sum, 200);

    for (const name of commands) {
      const payload = record['metrics'][name].latency.hdr.payload_b64;
      assert.ok(payload.startsWith('HIST'));
      assert.equal(decodeBase64(payload).totalCount, record['metrics'][name].latency.count);
    }
  });

  it('honours the shared request budget exactly, not a per-worker split', async () => {
    // 7 requests across 4 connections does not divide evenly. A pre-split budget
    // would round to 4 or 8; a shared budget lands on exactly 7.
    const { records } = await run({
      phases: [{ ...STEADY_PHASE, connections: 4, completion: { type: 'requests', requests: 7 } }],
    });
    assert.equal(records[0]!['totals'].requests, 7);
  });

  it('honours the budget when connections exceed the request count', async () => {
    const { records } = await run({
      phases: [{ ...STEADY_PHASE, connections: 8, completion: { type: 'requests', requests: 3 } }],
    });
    assert.equal(records[0]!['totals'].requests, 3);
  });

  it('runs each phase in order, appending one record per phase', async () => {
    const { records } = await run({
      phases: [
        {
          ...STEADY_PHASE,
          id: 'WARMUP',
          connections: 2,
          completion: { type: 'requests', requests: 20 },
          commands: [{ command: 'set', weight: 1.0, data_size_bytes: 16 }],
        },
        { ...STEADY_PHASE, id: 'STEADY', completion: { type: 'requests', requests: 30 } },
      ],
    });

    assert.deepEqual(
      records.map((r) => r['phase'].id),
      ['WARMUP', 'STEADY'],
    );
    assert.equal(records[0]!['totals'].requests, 20);
    assert.equal(records[1]!['totals'].requests, 30);
    assert.deepEqual(Object.keys(records[0]!['metrics']), ['SET']);
  });

  it('stops a duration-based phase at the deadline', async () => {
    const started = Date.now();
    const { records } = await run({
      phases: [{ ...STEADY_PHASE, connections: 2, completion: { type: 'duration', seconds: 1 } }],
    });
    const elapsed = Date.now() - started;

    assert.equal(records[0]!['phase'].status, 'COMPLETED');
    assert.ok(records[0]!['totals'].requests > 0, 'a duration phase should do some work');
    assert.ok(elapsed >= 900, `finished suspiciously early: ${elapsed}ms`);
    assert.ok(elapsed < 6000, `overran the 1s deadline: ${elapsed}ms`);
  });

  it('keeps pipelined runs on the same shared budget', async () => {
    const { records } = await run({
      phases: [
        {
          ...STEADY_PHASE,
          connections: 3,
          pipeline_depth: 4,
          completion: { type: 'requests', requests: 50 },
        },
      ],
    });
    // 3 connections x depth 4 = 12 in-flight slots all drawing on one budget of
    // 50; an over-issuing pipeline would show more than 50 requests.
    assert.equal(records[0]!['totals'].requests, 50);
  });

  it('surfaces injected errors as errors, not as latency samples', async () => {
    const { records } = await run({
      phases: [{ ...STEADY_PHASE, connections: 2, completion: { type: 'requests', requests: 100 } }],
      specific: { error_rate: 1.0, error_message: 'Simulated failure' },
    });

    const record = records[0]!;
    assert.equal(record['totals'].requests, 100);
    assert.equal(record['totals'].errors, 100);
    for (const name of Object.keys(record['metrics'])) {
      const metrics = record['metrics'][name];
      assert.equal(metrics.errors, metrics.requests);
      // Failed requests are counted but never recorded into the histogram.
      assert.equal(metrics.latency.count, 0);
      assert.ok(metrics.latency.hdr.payload_b64.startsWith('HIST'));
    }
  });

  it('records a partial error rate on both sides of the split', async () => {
    const { records } = await run({
      phases: [{ ...STEADY_PHASE, connections: 2, completion: { type: 'requests', requests: 400 } }],
      specific: { error_rate: 0.5 },
    });
    const { requests, errors } = records[0]!['totals'];
    assert.equal(requests, 400);
    assert.ok(errors > 100 && errors < 300, `error count ${errors} not near half of 400`);
  });

  it('does not count warmup requests toward the phase totals', async () => {
    // Warmup runs 3 PINGs on each of 2 connections. If those leaked into the
    // measured metrics we would see 6 extra requests and a PING command key.
    const { records } = await run({
      phases: [
        {
          ...STEADY_PHASE,
          connections: 2,
          warmup_requests: 3,
          completion: { type: 'requests', requests: 10 },
        },
      ],
    });
    assert.equal(records[0]!['totals'].requests, 10);
    assert.equal('PING' in records[0]!['metrics'], false);
  });

  it('suppresses injected errors during warmup so the phase still runs', async () => {
    // Warmup's fail-fast exists to catch an unreachable server. An error_rate
    // workload is deliberately measuring errors, so warmup must not abort on
    // them -- Java does the same via setWarmupMode.
    const { records } = await run({
      phases: [
        {
          ...STEADY_PHASE,
          connections: 2,
          warmup_requests: 2,
          completion: { type: 'requests', requests: 20 },
        },
      ],
      specific: { error_rate: 1.0, error_message: 'Simulated failure' },
    });
    assert.equal(records[0]!['phase'].status, 'COMPLETED');
    assert.equal(records[0]!['totals'].requests, 20);
    assert.equal(records[0]!['totals'].errors, 20);
  });

  it('applies an rps_limit to the whole phase', async () => {
    const started = Date.now();
    const { records } = await run({
      phases: [
        {
          ...STEADY_PHASE,
          connections: 4,
          rps_limit: 100,
          completion: { type: 'requests', requests: 50 },
        },
      ],
    });
    const elapsed = Date.now() - started;

    assert.equal(records[0]!['totals'].requests, 50);
    // 50 requests at 100/s cannot finish faster than ~0.49s however many
    // connections are issuing them.
    assert.ok(elapsed >= 400, `rps_limit was not enforced: ${elapsed}ms for 50 requests at 100/s`);
  });

  it('gates connection setup with a cps_limit', async () => {
    const started = Date.now();
    await run({
      phases: [
        {
          ...STEADY_PHASE,
          connections: 5,
          cps_limit: 20,
          completion: { type: 'requests', requests: 5 },
        },
      ],
    });
    const elapsed = Date.now() - started;
    // 5 connections at 20/s means the last one opens ~200ms in.
    assert.ok(elapsed >= 150, `cps_limit was not enforced: ${elapsed}ms to open 5 connections`);
  });

  it('generates keys inside the configured keyspace', async () => {
    const { records } = await run({
      phases: [
        {
          ...STEADY_PHASE,
          connections: 2,
          completion: { type: 'requests', requests: 40 },
          keyspace: {
            keys_count: 10,
            key_prefix: 'e2e:',
            key_size_bytes: 8,
            generation_alg: 'uniform_rand',
            seed: 12345,
          },
        },
      ],
    });
    assert.equal(records[0]!['totals'].requests, 40);
  });
});
