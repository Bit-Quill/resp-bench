/**
 * Latency metrics collection.
 *
 * Single-event-loop design: no locks are needed because `record` runs to
 * completion without awaiting, so concurrent workers never interleave inside it.
 * Latencies are clamped to 600s before recording, and errors are counted but not
 * recorded into the histogram -- matching the other engines.
 */

import type { CommandResult } from '../command/command.js';
import { HIGHEST_TRACKABLE_VALUE, newHistogram, type Histogram } from './hdrHistogram.js';

export class CommandMetrics {
  readonly commandName: string;
  requests = 0;
  errors = 0;
  /**
   * Created eagerly (like the Java reference) so the NDJSON `hdr` block and
   * summary are always present, even for a command that only ever errors -- an
   * empty histogram reports count 0 and zero percentiles.
   */
  readonly histogram: Histogram = newHistogram();

  constructor(commandName: string) {
    this.commandName = commandName;
  }

  record(result: CommandResult): void {
    this.requests += 1;
    if (result.success) {
      this.histogram.recordValue(Math.min(result.latencyMicros, HIGHEST_TRACKABLE_VALUE));
    } else {
      this.errors += 1;
    }
  }

  count(): number {
    return this.histogram.totalCount;
  }

  /**
   * Lowest recorded latency, matching Java's `Histogram.getMinValue()`.
   *
   * Deliberately NOT `minNonZeroValue`: that skips a legitimately recorded 0us
   * sample, and on an empty histogram it returns Number.MAX_SAFE_INTEGER, which
   * would land in the NDJSON as a nonsense min. `getValueAtPercentile(0)` returns
   * 0 when empty and Java's `getMinValue()` otherwise -- verified equal for
   * 1us..599s (and it is what the Ruby encoder uses).
   */
  min(): number {
    return this.histogram.getValueAtPercentile(0);
  }

  /**
   * Highest recorded latency, matching Java's `Histogram.getMaxValue()`.
   *
   * Deliberately NOT `maxValue`: hdr-histogram-js returns the raw recorded
   * sample there, while Java returns the *bucket's* highest equivalent value. At
   * 3 significant figures those diverge above ~1000us -- recording 50000us gives
   * 50000 in JS but 50015 in Java -- which would make summary.max quietly
   * incomparable across engines. `getValueAtPercentile(100)` is Java's value.
   */
  max(): number {
    return this.histogram.getValueAtPercentile(100);
  }

  percentile(pct: number): number {
    return this.histogram.getValueAtPercentile(pct);
  }
}

export class MetricsCollector {
  readonly commandMetrics = new Map<string, CommandMetrics>();
  totalRequests = 0;
  totalErrors = 0;
  private startTimeMs: number | null = null;
  private endTimeMs: number | null = null;

  start(): void {
    this.startTimeMs = Date.now();
  }

  stop(): void {
    this.endTimeMs = Date.now();
  }

  record(result: CommandResult): void {
    this.totalRequests += 1;
    if (!result.success) this.totalErrors += 1;

    let metrics = this.commandMetrics.get(result.commandName);
    if (metrics === undefined) {
      metrics = new CommandMetrics(result.commandName);
      this.commandMetrics.set(result.commandName, metrics);
    }
    metrics.record(result);
  }

  startTime(): number {
    return this.startTimeMs ?? 0;
  }

  endTime(): number {
    return this.endTimeMs ?? 0;
  }

  durationMillis(): number {
    if (this.startTimeMs === null || this.endTimeMs === null) return 0;
    return this.endTimeMs - this.startTimeMs;
  }
}
