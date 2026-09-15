/**
 * The interface every driver implements.
 *
 * One client instance maps to exactly one transport connection (the
 * `client == connection` invariant shared by all engines); the engine never
 * shares a client across workers.
 *
 * `measure()` is the single place latency is captured, so every driver reports it
 * identically: `process.hrtime.bigint()` around the awaited command, truncated to
 * whole microseconds, recorded even when the command throws.
 */

import type { DriverConfig } from '../config/driverConfig.js';
import type { TimedResult } from './timedResult.js';

export interface BenchmarkClient {
  connect(host: string, port: number, config: DriverConfig): Promise<void>;
  ping(): Promise<TimedResult<string>>;
  get(key: string): Promise<TimedResult<string>>;
  set(key: string, value: Buffer): Promise<TimedResult<string>>;
  close(): Promise<void>;
  driverVersion(): string;
  /** Secondary driver version, for composite drivers only. */
  secondaryDriverVersion?(): string | null;
  /**
   * Mark the client as warming up, mirroring Java's `setWarmupMode`
   * (BenchmarkEngine.java:212-237).
   *
   * Real drivers ignore this. The recording driver uses it to suppress simulated
   * errors so that an `error_rate` workload does not abort in warmup — the
   * warmup fail-fast is meant to catch an unreachable server, not injected
   * errors the phase is deliberately measuring.
   */
  setWarmupMode?(warmup: boolean): void;
}

const NANOS_PER_MICRO = 1000n;

/**
 * Await `operation` and record its latency in microseconds.
 *
 * Errors are captured, not thrown: the engine records a failed request and keeps
 * going, matching the other engines. Latency is measured on the error path too.
 */
export async function measure<T>(operation: () => Promise<T>): Promise<TimedResult<T>> {
  const start = process.hrtime.bigint();
  try {
    const value = await operation();
    const latencyMicros = Number((process.hrtime.bigint() - start) / NANOS_PER_MICRO);
    return { value, latencyMicros };
  } catch (error) {
    const latencyMicros = Number((process.hrtime.bigint() - start) / NANOS_PER_MICRO);
    return {
      value: null,
      latencyMicros,
      error: error instanceof Error ? error : new Error(String(error)),
    };
  }
}
