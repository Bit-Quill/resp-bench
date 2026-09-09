/**
 * In-memory recording client for server-free testing.
 *
 * Records operations and supports simulated latency and error injection via
 * `specific_driver_config` (`operation_delay_micros`, `delay_variation_micros`,
 * `error_rate`, `error_message`). This lets the integration tests exercise the
 * whole engine without a live server, mirroring the Ruby/Python recording
 * drivers.
 */

import type { DriverConfig } from '../../config/driverConfig.js';
import type { BenchmarkClient } from '../benchmarkClient.js';
import type { TimedResult } from '../timedResult.js';

export interface RecordedOperation {
  command: string;
  key: string | null;
  value: Buffer | null;
  success: boolean;
  errorMessage: string | null;
}

const NANOS_PER_MICRO = 1000n;

function readNumber(source: Record<string, unknown>, key: string, fallback: number): number {
  const value = source[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

/** Sleep for whole microseconds, tolerating setTimeout's ~1ms floor. */
function sleepMicros(micros: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, micros / 1000));
}

export class RecordingClient implements BenchmarkClient {
  readonly operations: RecordedOperation[] = [];
  private readonly storedData = new Map<string, Buffer>();
  private operationDelayMicros = 0;
  private delayVariationMicros = 0;
  private errorRate = 0;
  private errorMessage = 'Simulated error';
  private warmupMode = false;

  async connect(_host: string, _port: number, config: DriverConfig): Promise<void> {
    const cfg = config.specificDriverConfig;
    this.operationDelayMicros = readNumber(cfg, 'operation_delay_micros', 0);
    this.delayVariationMicros = readNumber(cfg, 'delay_variation_micros', 0);
    this.errorRate = readNumber(cfg, 'error_rate', 0);
    const message = cfg['error_message'];
    if (typeof message === 'string') this.errorMessage = message;
    this.record('CONNECT', null, null, true, null);
  }

  async ping(): Promise<TimedResult<string>> {
    const { latencyMicros, success } = await this.simulate();
    this.record('PING', null, null, success, success ? null : this.errorMessage);
    return success
      ? { value: 'PONG', latencyMicros }
      : { value: null, latencyMicros, error: new Error(this.errorMessage) };
  }

  async get(key: string): Promise<TimedResult<string>> {
    const { latencyMicros, success } = await this.simulate();
    this.record('GET', key, null, success, success ? null : this.errorMessage);
    if (!success) return { value: null, latencyMicros, error: new Error(this.errorMessage) };
    const stored = this.storedData.get(key);
    return { value: stored === undefined ? null : stored.toString('latin1'), latencyMicros };
  }

  async set(key: string, value: Buffer): Promise<TimedResult<string>> {
    const { latencyMicros, success } = await this.simulate();
    if (success) this.storedData.set(key, value);
    this.record('SET', key, value, success, success ? null : this.errorMessage);
    return success
      ? { value: 'OK', latencyMicros }
      : { value: null, latencyMicros, error: new Error(this.errorMessage) };
  }

  async close(): Promise<void> {
    this.record('CLOSE', null, null, true, null);
  }

  driverVersion(): string {
    return '1.0.0';
  }

  /**
   * Suppress simulated errors during warmup, matching Java's recording client.
   * The engine's warmup fail-fast exists to catch an unreachable server, so
   * injected errors must not trip it.
   */
  setWarmupMode(warmup: boolean): void {
    this.warmupMode = warmup;
  }

  private record(
    command: string,
    key: string | null,
    value: Buffer | null,
    success: boolean,
    errorMessage: string | null,
  ): void {
    this.operations.push({ command, key, value, success, errorMessage });
  }

  private async simulate(): Promise<{ latencyMicros: number; success: boolean }> {
    const start = process.hrtime.bigint();
    const delayMicros = this.calculateDelayMicros();
    if (delayMicros > 0) await sleepMicros(delayMicros);
    const latencyMicros = Number((process.hrtime.bigint() - start) / NANOS_PER_MICRO);
    return { latencyMicros, success: !this.shouldSimulateError() };
  }

  private calculateDelayMicros(): number {
    if (this.operationDelayMicros <= 0) return 0;
    let delay = this.operationDelayMicros;
    if (this.delayVariationMicros > 0) {
      const spread = 2 * this.delayVariationMicros + 1;
      const variation = Math.floor(Math.random() * spread) - this.delayVariationMicros;
      delay = Math.max(0, delay + variation);
    }
    return delay;
  }

  private shouldSimulateError(): boolean {
    if (this.warmupMode) return false;
    if (this.errorRate <= 0) return false;
    if (this.errorRate >= 1) return true;
    return Math.random() < this.errorRate;
  }
}
