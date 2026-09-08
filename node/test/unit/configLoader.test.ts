import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';

import { DEFAULT_DATA_SIZE_BYTES } from '../../src/config/commandConfig.js';
import {
  DEFAULT_KEY_PREFIX,
  DEFAULT_KEY_SIZE_BYTES,
} from '../../src/config/keyspaceConfig.js';
import { ConfigError, ConfigLoader } from '../../src/config/loader.js';

function writeTempJson(name: string, data: unknown): string {
  const dir = mkdtempSync(join(tmpdir(), 'resp-bench-node-'));
  const path = join(dir, name);
  writeFileSync(path, JSON.stringify(data), 'utf8');
  return path;
}

const MINIMAL_PHASE = {
  id: 'STEADY',
  connections: 2,
  completion: { type: 'requests', requests: 100 },
  keyspace: { keys_count: 50 },
  commands: [{ command: 'get', weight: 1.0 }],
};

describe('ConfigLoader — driver config', () => {
  it('parses every schema field', () => {
    const config = ConfigLoader.parseDriverConfig({
      schema_version: '1.0',
      description: 'test driver',
      driver_id: 'ioredis',
      mode: 'cluster',
      command_timeout_ms: 10_000,
      tls: { enabled: true, ca_path: '/tmp/ca.pem' },
      auth: { username: 'u', password: 'p' },
      specific_driver_config: { secondary_driver_id: 'other' },
    });

    assert.equal(config.schemaVersion, '1.0');
    assert.equal(config.description, 'test driver');
    assert.equal(config.driverId, 'ioredis');
    assert.equal(config.mode, 'cluster');
    assert.equal(config.commandTimeoutMs, 10_000);
    assert.equal(config.tlsEnabled(), true);
    assert.equal(config.hasAuth(), true);
    assert.equal(config.isCluster(), true);
    assert.equal(config.isStandalone(), false);
    assert.equal(config.secondaryDriverId(), 'other');
  });

  it('applies defaults for the optional fields', () => {
    const config = ConfigLoader.parseDriverConfig({ driver_id: 'ioredis' });
    assert.equal(config.schemaVersion, '1.0');
    assert.equal(config.mode, 'standalone');
    assert.equal(config.commandTimeoutMs, null);
    assert.equal(config.tlsEnabled(), false);
    assert.equal(config.hasAuth(), false);
    assert.deepEqual(config.specificDriverConfig, {});
    assert.equal(config.secondaryDriverId(), null);
  });

  it('treats an empty username and password as no auth', () => {
    const config = ConfigLoader.parseDriverConfig({
      driver_id: 'ioredis',
      auth: { username: '', password: '' },
    });
    assert.equal(config.hasAuth(), false);
  });

  it('rejects a missing driver_id', () => {
    assert.throws(() => ConfigLoader.parseDriverConfig({ mode: 'standalone' }), ConfigError);
  });

  it('rejects an unknown mode', () => {
    assert.throws(
      () => ConfigLoader.parseDriverConfig({ driver_id: 'ioredis', mode: 'galaxy' }),
      /must be standalone, cluster or sentinel/,
    );
  });

  it('loads the real repo driver configs', () => {
    const path = writeTempJson('driver.json', {
      schema_version: '1.0',
      description: 'valkey-glide-node client - default configuration',
      driver_id: 'valkey-glide-node',
      mode: 'standalone',
      specific_driver_config: {},
    });
    assert.equal(ConfigLoader.loadDriverConfig(path).driverId, 'valkey-glide-node');
  });

  it('reports a readable error for a missing file', () => {
    assert.throws(() => ConfigLoader.loadDriverConfig('/nope/missing.json'), /cannot read driver config/);
  });

  it('reports a readable error for malformed JSON', () => {
    const dir = mkdtempSync(join(tmpdir(), 'resp-bench-node-'));
    const path = join(dir, 'bad.json');
    writeFileSync(path, '{ not json', 'utf8');
    assert.throws(() => ConfigLoader.loadDriverConfig(path), /is not valid JSON/);
  });
});

describe('ConfigLoader — workload config', () => {
  it('parses a full workload', () => {
    const workload = ConfigLoader.parseWorkloadConfig({
      schema_version: '1.0',
      benchmark_profile: { name: 'Reference', description: 'd', version: '1.0.0' },
      phases: [
        {
          id: 'WARMUP',
          description: 'populate',
          connections: 1,
          cps_limit: -1,
          rps_limit: -1,
          pipeline_depth: 4,
          warmup_requests: 3,
          completion: { type: 'requests', requests: 1_000_000 },
          keyspace: {
            keys_count: 1_000_000,
            key_size_bytes: 16,
            key_prefix: 'bench:',
            generation_alg: 'uniform_rand',
            seed: 12345,
          },
          commands: [
            { command: 'get', weight: 0.8 },
            { command: 'set', weight: 0.2, data_size_bytes: 512 },
          ],
        },
      ],
    });

    assert.equal(workload.name(), 'Reference');
    assert.equal(workload.phases.length, 1);
    const phase = workload.phases[0]!;
    assert.equal(phase.id, 'WARMUP');
    assert.equal(phase.connections, 1);
    assert.equal(phase.pipelineDepth, 4);
    assert.equal(phase.effectivePipelineDepth(), 4);
    assert.equal(phase.warmupRequests, 3);
    assert.equal(phase.hasCpsLimit(), false);
    assert.equal(phase.hasRpsLimit(), false);
    assert.equal(phase.completion.isRequestBased(), true);
    assert.equal(phase.completion.totalRequests(), 1_000_000);
    assert.equal(phase.keyspace.isUniformRand(), true);
    assert.equal(phase.keyspace.seedValue(), 12345);
    assert.equal(phase.commands[1]!.dataSizeBytes, 512);
  });

  it('applies the cross-engine defaults', () => {
    const workload = ConfigLoader.parseWorkloadConfig({ phases: [MINIMAL_PHASE] });
    const phase = workload.phases[0]!;
    assert.equal(phase.cpsLimit, -1);
    assert.equal(phase.rpsLimit, -1);
    assert.equal(phase.pipelineDepth, 1);
    assert.equal(phase.warmupRequests, 1);
    assert.equal(phase.description, null);
    assert.equal(phase.keyspace.keySizeBytes, DEFAULT_KEY_SIZE_BYTES);
    assert.equal(phase.keyspace.keyPrefix, DEFAULT_KEY_PREFIX);
    assert.equal(phase.keyspace.isSequentialInt(), true);
    assert.equal(phase.keyspace.seedValue(), 0);
    assert.equal(phase.commands[0]!.dataSizeBytes, DEFAULT_DATA_SIZE_BYTES);
    assert.equal(phase.commands[0]!.weight, 1.0);
    assert.equal(workload.name(), 'unnamed');
  });

  it('treats an explicit null as absent, not as null', () => {
    // The Ruby/Python engines coerce nulls to defaults; a null leaking through
    // would surface as NaN padding or a null prefix deep in the worker loop.
    const workload = ConfigLoader.parseWorkloadConfig({
      phases: [
        {
          ...MINIMAL_PHASE,
          cps_limit: null,
          rps_limit: null,
          pipeline_depth: null,
          warmup_requests: null,
          keyspace: { keys_count: 50, key_size_bytes: null, key_prefix: null, generation_alg: null },
          commands: [{ command: 'set', weight: null, data_size_bytes: null }],
        },
      ],
    });
    const phase = workload.phases[0]!;
    assert.equal(phase.cpsLimit, -1);
    assert.equal(phase.pipelineDepth, 1);
    assert.equal(phase.warmupRequests, 1);
    assert.equal(phase.keyspace.keySizeBytes, DEFAULT_KEY_SIZE_BYTES);
    assert.equal(phase.keyspace.keyPrefix, DEFAULT_KEY_PREFIX);
    assert.equal(phase.keyspace.generationAlg, 'sequential_int');
    assert.equal(phase.commands[0]!.weight, 1.0);
    assert.equal(phase.commands[0]!.dataSizeBytes, DEFAULT_DATA_SIZE_BYTES);
  });

  it('lower-cases command names', () => {
    const workload = ConfigLoader.parseWorkloadConfig({
      phases: [{ ...MINIMAL_PHASE, commands: [{ command: 'GET', weight: 1 }] }],
    });
    assert.equal(workload.phases[0]!.commands[0]!.command, 'get');
  });

  it('recognises limits when set', () => {
    const workload = ConfigLoader.parseWorkloadConfig({
      phases: [{ ...MINIMAL_PHASE, cps_limit: 10, rps_limit: 500 }],
    });
    const phase = workload.phases[0]!;
    assert.equal(phase.hasCpsLimit(), true);
    assert.equal(phase.hasRpsLimit(), true);
  });

  it('rejects a workload with no phases', () => {
    assert.throws(() => ConfigLoader.parseWorkloadConfig({ phases: [] }), /non-empty "phases"/);
    assert.throws(() => ConfigLoader.parseWorkloadConfig({}), /non-empty "phases"/);
  });

  it('rejects a phase with no commands', () => {
    assert.throws(
      () => ConfigLoader.parseWorkloadConfig({ phases: [{ ...MINIMAL_PHASE, commands: [] }] }),
      /non-empty "commands"/,
    );
  });

  it('rejects non-positive connections', () => {
    assert.throws(
      () => ConfigLoader.parseWorkloadConfig({ phases: [{ ...MINIMAL_PHASE, connections: 0 }] }),
      /"connections" must be positive/,
    );
  });

  it('rejects a completion type that carries no target', () => {
    assert.throws(
      () =>
        ConfigLoader.parseWorkloadConfig({
          phases: [{ ...MINIMAL_PHASE, completion: { type: 'duration' } }],
        }),
      /requires a positive "seconds"/,
    );
    assert.throws(
      () =>
        ConfigLoader.parseWorkloadConfig({
          phases: [{ ...MINIMAL_PHASE, completion: { type: 'requests' } }],
        }),
      /requires a positive "requests"/,
    );
  });

  it('rejects an unknown generation_alg', () => {
    assert.throws(
      () =>
        ConfigLoader.parseWorkloadConfig({
          phases: [{ ...MINIMAL_PHASE, keyspace: { keys_count: 10, generation_alg: 'zipf' } }],
        }),
      /sequential_int or uniform_rand/,
    );
  });

  it('rejects a weight outside 0..1, as Java does', () => {
    assert.throws(
      () =>
        ConfigLoader.parseWorkloadConfig({
          phases: [{ ...MINIMAL_PHASE, commands: [{ command: 'get', weight: 5 }] }],
        }),
      /"weight" must be between 0 and 1/,
    );
  });
});
