import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';

import { MetricsCollector } from '../../src/metrics/collector.js';
import { decodeBase64 } from '../../src/metrics/hdrHistogram.js';
import { NdjsonWriter } from '../../src/metrics/ndjsonWriter.js';

function tempPath(name = 'metrics.ndjson'): string {
  return join(mkdtempSync(join(tmpdir(), 'resp-bench-node-')), name);
}

function collectorWith(samples: Array<{ name: string; latency: number; ok?: boolean }>) {
  const collector = new MetricsCollector();
  collector.start();
  for (const { name, latency, ok = true } of samples) {
    collector.record({ commandName: name, latencyMicros: latency, success: ok });
  }
  collector.stop();
  return collector;
}

function readRecords(path: string): Array<Record<string, any>> {
  return readFileSync(path, 'utf8')
    .trimEnd()
    .split('\n')
    .map((line) => JSON.parse(line));
}

describe('NdjsonWriter', () => {
  it('writes the documented record shape', () => {
    const path = tempPath();
    const writer = new NdjsonWriter(path);
    writer.setMetadata({
      commitId: 'abc123',
      driverId: 'ioredis',
      primaryDriverVersion: '5.11.1',
    });
    writer.writePhaseResults({
      phaseId: 'STEADY',
      status: 'COMPLETED',
      connections: 4,
      collector: collectorWith([
        { name: 'GET', latency: 100 },
        { name: 'GET', latency: 200 },
        { name: 'SET', latency: 300 },
      ]),
    });

    const [record] = readRecords(path);
    assert.ok(record);

    assert.deepEqual(Object.keys(record).sort(), ['metadata', 'metrics', 'phase', 'totals']);
    assert.equal(record['metadata'].commit_id, 'abc123');
    assert.equal(record['metadata'].driver_id, 'ioredis');
    assert.equal(record['metadata'].primary_driver_version, '5.11.1');
    assert.ok(record['metadata'].timestamp);
    // Absent secondary driver fields must be omitted, not null.
    assert.equal('secondary_driver_id' in record['metadata'], false);

    assert.deepEqual(Object.keys(record['phase']).sort(), [
      'connections',
      'duration_ms',
      'finish_timestamp',
      'id',
      'start_timestamp',
      'status',
    ]);
    assert.equal(record['phase'].id, 'STEADY');
    assert.equal(record['phase'].status, 'COMPLETED');
    assert.equal(record['phase'].connections, 4);
    assert.match(record['phase'].start_timestamp, /^\d{4}-\d{2}-\d{2}T.*Z$/);

    assert.deepEqual(record['totals'], { requests: 3, errors: 0 });

    assert.deepEqual(Object.keys(record['metrics']).sort(), ['GET', 'SET']);
    const get = record['metrics'].GET;
    assert.equal(get.requests, 2);
    assert.equal(get.errors, 0);
    assert.equal(get.latency.unit, 'us');
    assert.equal(get.latency.count, 2);
    assert.deepEqual(Object.keys(get.latency.summary).sort(), [
      'max',
      'min',
      'p50',
      'p95',
      'p99',
      'p999',
    ]);
    assert.equal(get.latency.hdr.format, 'hdr');
    assert.equal(get.latency.hdr.sigfig, 3);
    assert.ok(get.latency.hdr.payload_b64.startsWith('HIST'));
  });

  it('writes one compact line per phase and appends across phases', () => {
    const path = tempPath();
    const writer = new NdjsonWriter(path);
    writer.setMetadata({ driverId: 'ioredis' });
    for (const phaseId of ['WARMUP', 'STEADY']) {
      writer.writePhaseResults({
        phaseId,
        status: 'COMPLETED',
        connections: 1,
        collector: collectorWith([{ name: 'GET', latency: 10 }]),
      });
    }

    const raw = readFileSync(path, 'utf8');
    assert.equal(raw.endsWith('\n'), true);
    const lines = raw.trimEnd().split('\n');
    assert.equal(lines.length, 2);
    // NDJSON requires no embedded newlines -- no pretty printing.
    for (const line of lines) assert.doesNotMatch(line, /\n/);
    assert.deepEqual(
      lines.map((line) => JSON.parse(line).phase.id),
      ['WARMUP', 'STEADY'],
    );
  });

  it('creates the parent directory when it does not exist', () => {
    const path = join(mkdtempSync(join(tmpdir(), 'resp-bench-node-')), 'nested', 'deep', 'm.ndjson');
    const writer = new NdjsonWriter(path);
    writer.setMetadata({ driverId: 'ioredis' });
    writer.writePhaseResults({
      phaseId: 'P',
      status: 'COMPLETED',
      connections: 1,
      collector: collectorWith([{ name: 'GET', latency: 10 }]),
    });
    assert.equal(readRecords(path).length, 1);
  });

  it('emits an hdr block for a command that only ever errored', () => {
    // Matches Java: the histogram is created eagerly, so analysis tooling can
    // always read metrics.<CMD>.latency.hdr without a null check.
    const path = tempPath();
    const writer = new NdjsonWriter(path);
    writer.setMetadata({ driverId: 'ioredis' });
    writer.writePhaseResults({
      phaseId: 'ERRORS',
      status: 'COMPLETED',
      connections: 1,
      collector: collectorWith([
        { name: 'GET', latency: 50, ok: false },
        { name: 'GET', latency: 60, ok: false },
      ]),
    });

    const [record] = readRecords(path);
    const get = record!['metrics'].GET;
    assert.equal(get.requests, 2);
    assert.equal(get.errors, 2);
    assert.equal(get.latency.count, 0);
    assert.deepEqual(get.latency.summary, { min: 0, p50: 0, p95: 0, p99: 0, p999: 0, max: 0 });
    assert.ok(get.latency.hdr.payload_b64.startsWith('HIST'));
    assert.equal(decodeBase64(get.latency.hdr.payload_b64).totalCount, 0);
    assert.deepEqual(record!['totals'], { requests: 2, errors: 2 });
  });

  it('omits the metadata block entirely when nothing identifies the run', () => {
    const path = tempPath();
    const writer = new NdjsonWriter(path);
    writer.writePhaseResults({
      phaseId: 'P',
      status: 'COMPLETED',
      connections: 1,
      collector: collectorWith([{ name: 'GET', latency: 10 }]),
    });
    assert.equal('metadata' in readRecords(path)[0]!, false);
  });

  it('carries the secondary driver fields when present', () => {
    const path = tempPath();
    const writer = new NdjsonWriter(path);
    writer.setMetadata({
      driverId: 'composite',
      primaryDriverVersion: '1.0.0',
      secondaryDriverId: 'ioredis',
      secondaryDriverVersion: '5.11.1',
    });
    writer.writePhaseResults({
      phaseId: 'P',
      status: 'COMPLETED',
      connections: 1,
      collector: collectorWith([{ name: 'GET', latency: 10 }]),
    });
    const metadata = readRecords(path)[0]!['metadata'];
    assert.equal(metadata.secondary_driver_id, 'ioredis');
    assert.equal(metadata.secondary_driver_version, '5.11.1');
  });
});
