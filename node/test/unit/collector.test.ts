import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { CommandMetrics, MetricsCollector } from '../../src/metrics/collector.js';
import { HIGHEST_TRACKABLE_VALUE } from '../../src/metrics/hdrHistogram.js';

describe('CommandMetrics', () => {
  it('counts requests and records only successes into the histogram', () => {
    const metrics = new CommandMetrics('GET');
    metrics.record({ commandName: 'GET', latencyMicros: 100, success: true });
    metrics.record({ commandName: 'GET', latencyMicros: 200, success: true });
    metrics.record({ commandName: 'GET', latencyMicros: 300, success: false });

    assert.equal(metrics.requests, 3);
    assert.equal(metrics.errors, 1);
    // The failed request's latency must not skew the distribution.
    assert.equal(metrics.count(), 2);
    assert.equal(metrics.max(), 200);
  });

  it('reports zeros for an empty histogram rather than sentinels', () => {
    // minNonZeroValue would return Number.MAX_SAFE_INTEGER here and land a
    // nonsense min in the NDJSON.
    const metrics = new CommandMetrics('GET');
    assert.equal(metrics.count(), 0);
    assert.equal(metrics.min(), 0);
    assert.equal(metrics.max(), 0);
    assert.equal(metrics.percentile(50), 0);
    assert.equal(metrics.percentile(99.9), 0);
  });

  it('reports a legitimately recorded 0us sample as min 0', () => {
    const metrics = new CommandMetrics('PING');
    metrics.record({ commandName: 'PING', latencyMicros: 0, success: true });
    metrics.record({ commandName: 'PING', latencyMicros: 5, success: true });
    assert.equal(metrics.min(), 0);
  });

  it('matches Java getMinValue/getMaxValue bucket quantization', () => {
    // Verified against org.HdrHistogram.Histogram(1, 600_000_000, 3): at 3
    // significant figures Java reports the bucket's equivalent bounds, not the
    // raw sample. hdr-histogram-js' maxValue/minNonZeroValue do NOT do this, so
    // these anchors keep summary.min/max comparable across engines.
    for (const [recorded, expectedMin, expectedMax] of [
      [1, 1, 1],
      [100, 100, 100],
      [1234, 1234, 1234],
      [50_000, 49_984, 50_015],
      [599_000_000, 598_736_896, 599_261_183],
    ] as const) {
      const metrics = new CommandMetrics('GET');
      metrics.record({ commandName: 'GET', latencyMicros: recorded, success: true });
      assert.equal(metrics.min(), expectedMin, `min for ${recorded}`);
      assert.equal(metrics.max(), expectedMax, `max for ${recorded}`);
    }
  });

  it('clamps a latency above the trackable range instead of throwing', () => {
    const metrics = new CommandMetrics('GET');
    metrics.record({
      commandName: 'GET',
      latencyMicros: HIGHEST_TRACKABLE_VALUE * 2,
      success: true,
    });
    assert.equal(metrics.count(), 1);
  });
});

describe('MetricsCollector', () => {
  it('aggregates totals across commands', () => {
    const collector = new MetricsCollector();
    collector.record({ commandName: 'GET', latencyMicros: 10, success: true });
    collector.record({ commandName: 'SET', latencyMicros: 20, success: true });
    collector.record({ commandName: 'SET', latencyMicros: 30, success: false });

    assert.equal(collector.totalRequests, 3);
    assert.equal(collector.totalErrors, 1);
    assert.deepEqual([...collector.commandMetrics.keys()], ['GET', 'SET']);
    assert.equal(collector.commandMetrics.get('SET')!.requests, 2);
  });

  it('preserves first-seen command order for stable NDJSON output', () => {
    const collector = new MetricsCollector();
    collector.record({ commandName: 'SET', latencyMicros: 1, success: true });
    collector.record({ commandName: 'GET', latencyMicros: 1, success: true });
    assert.deepEqual([...collector.commandMetrics.keys()], ['SET', 'GET']);
  });

  it('reports zero duration until both start and stop have run', () => {
    const collector = new MetricsCollector();
    assert.equal(collector.durationMillis(), 0);
    collector.start();
    assert.equal(collector.durationMillis(), 0);
    collector.stop();
    assert.ok(collector.durationMillis() >= 0);
  });
});
