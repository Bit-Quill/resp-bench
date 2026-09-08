/**
 * Live-server tests for the real drivers.
 *
 * Skipped unless VALKEY_HOST/VALKEY_PORT point at a running server, matching the
 * Java/Ruby/C# integration suites (`make node-integration-test` starts one).
 */

import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';

import { BenchmarkClientFactory } from '../../src/client/factory.js';
import { ConfigLoader } from '../../src/config/loader.js';
import { BenchmarkEngine, type Logger } from '../../src/engine/benchmark.js';
import { decodeBase64 } from '../../src/metrics/hdrHistogram.js';

const HOST = process.env['VALKEY_HOST'] ?? 'localhost';
const PORT = Number(process.env['VALKEY_PORT'] ?? 6379);
const REAL_DRIVERS = ['valkey-glide-node', 'ioredis', 'iovalkey'] as const;

const silentLogger: Logger = { info: () => {}, warn: () => {}, error: () => {} };

/** True when VALKEY_HOST is set, i.e. a server is expected to be reachable. */
const enabled = process.env['VALKEY_HOST'] !== undefined;
const skip = enabled ? false : 'set VALKEY_HOST to run the live-server tests';

function driverConfig(driverId: string, extra: Record<string, unknown> = {}) {
  return ConfigLoader.parseDriverConfig({
    schema_version: '1.0',
    driver_id: driverId,
    mode: 'standalone',
    specific_driver_config: {},
    ...extra,
  });
}

for (const driverId of REAL_DRIVERS) {
  describe(`${driverId} against a live server`, { skip }, () => {
    it('connects, pings, sets, gets and closes', async () => {
      const client = await BenchmarkClientFactory.createAndConnect(HOST, PORT, driverConfig(driverId));
      try {
        const ping = await client.ping();
        assert.equal(ping.error, undefined);
        assert.equal(ping.value, 'PONG');
        assert.ok(ping.latencyMicros >= 0);

        const key = `node-it:${driverId}:${process.pid}`;
        const payload = Buffer.from('x'.repeat(64), 'latin1');

        const set = await client.set(key, payload);
        assert.equal(set.error, undefined);
        assert.equal(set.value, 'OK');

        const get = await client.get(key);
        assert.equal(get.error, undefined);
        // Every driver must return the same decoded shape, or the engines are
        // charged for different work: a string of the payload's length.
        assert.equal(typeof get.value, 'string');
        assert.equal(get.value, payload.toString('latin1'));
      } finally {
        await client.close();
      }
    });

    it('returns null for a missing key rather than erroring', async () => {
      const client = await BenchmarkClientFactory.createAndConnect(HOST, PORT, driverConfig(driverId));
      try {
        const get = await client.get(`node-it:absent:${process.pid}:${Math.random()}`);
        assert.equal(get.error, undefined);
        assert.equal(get.value, null);
      } finally {
        await client.close();
      }
    });

    it('reports a real driver version, not "unknown"', async () => {
      const client = await BenchmarkClientFactory.create(driverId);
      assert.match(client.driverVersion(), /^\d+\.\d+\.\d+/);
    });

    it('applies command_timeout_ms without breaking normal commands', async () => {
      const client = await BenchmarkClientFactory.createAndConnect(
        HOST,
        PORT,
        driverConfig(driverId, { command_timeout_ms: 5000 }),
      );
      try {
        assert.equal((await client.ping()).error, undefined);
      } finally {
        await client.close();
      }
    });

    it('runs a short workload and writes decodable metrics', async () => {
      const metricsPath = join(mkdtempSync(join(tmpdir(), 'resp-bench-node-')), 'metrics.ndjson');
      await new BenchmarkEngine({
        host: HOST,
        port: PORT,
        driverConfig: driverConfig(driverId),
        workloadConfig: ConfigLoader.parseWorkloadConfig({
          benchmark_profile: { name: `${driverId} smoke` },
          phases: [
            {
              id: 'STEADY',
              connections: 2,
              warmup_requests: 1,
              completion: { type: 'requests', requests: 200 },
              keyspace: { keys_count: 100, key_prefix: 'node-it:', key_size_bytes: 16 },
              commands: [
                { command: 'set', weight: 0.5, data_size_bytes: 64 },
                { command: 'get', weight: 0.5 },
              ],
            },
          ],
        }),
        metricsPath,
        commitId: 'integration-test',
        logger: silentLogger,
      }).run();

      const record = JSON.parse(readFileSync(metricsPath, 'utf8').trimEnd().split('\n')[0]!);
      assert.equal(record.phase.status, 'COMPLETED');
      assert.equal(record.totals.requests, 200);
      assert.equal(record.totals.errors, 0, 'a healthy server should produce no errors');
      assert.equal(record.metadata.driver_id, driverId);
      assert.match(record.metadata.primary_driver_version, /^\d+\.\d+\.\d+/);
      for (const name of Object.keys(record.metrics)) {
        const latency = record.metrics[name].latency;
        assert.ok(latency.hdr.payload_b64.startsWith('HIST'));
        assert.equal(decodeBase64(latency.hdr.payload_b64).totalCount, latency.count);
        assert.ok(latency.summary.p50 >= 0);
        assert.ok(latency.summary.max >= latency.summary.p50);
      }
    });

    it('runs a pipelined workload', async () => {
      const metricsPath = join(mkdtempSync(join(tmpdir(), 'resp-bench-node-')), 'metrics.ndjson');
      await new BenchmarkEngine({
        host: HOST,
        port: PORT,
        driverConfig: driverConfig(driverId),
        workloadConfig: ConfigLoader.parseWorkloadConfig({
          phases: [
            {
              id: 'PIPELINED',
              connections: 2,
              pipeline_depth: 8,
              warmup_requests: 1,
              completion: { type: 'requests', requests: 200 },
              keyspace: { keys_count: 100, key_prefix: 'node-it:', key_size_bytes: 16 },
              commands: [{ command: 'get', weight: 1.0 }],
            },
          ],
        }),
        metricsPath,
        logger: silentLogger,
      }).run();

      const record = JSON.parse(readFileSync(metricsPath, 'utf8').trimEnd().split('\n')[0]!);
      assert.equal(record.phase.status, 'COMPLETED');
      // 2 connections x depth 8 = 16 in-flight slots on one shared budget of 200.
      assert.equal(record.totals.requests, 200);
      assert.equal(record.totals.errors, 0);
    });
  });
}

describe('unreachable server', { skip }, () => {
  it('fails fast instead of recording a phase of pure errors', async () => {
    // Port 1 is reserved and never listening. A dead server must abort the run
    // with a clear error, not silently produce 100% error metrics.
    await assert.rejects(
      () =>
        new BenchmarkEngine({
          host: '127.0.0.1',
          port: 1,
          driverConfig: driverConfig('ioredis'),
          workloadConfig: ConfigLoader.parseWorkloadConfig({
            phases: [
              {
                id: 'STEADY',
                connections: 1,
                warmup_requests: 1,
                completion: { type: 'requests', requests: 10 },
                keyspace: { keys_count: 10 },
                commands: [{ command: 'get', weight: 1.0 }],
              },
            ],
          }),
          metricsPath: join(mkdtempSync(join(tmpdir(), 'resp-bench-node-')), 'metrics.ndjson'),
          logger: silentLogger,
        }).run(),
    );
  });
});
