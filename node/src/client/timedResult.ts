/**
 * The outcome of a single measured command.
 *
 * `error === undefined` means success. Latency is populated on both paths so a
 * failure still contributes a measured duration, matching the other engines.
 */
export interface TimedResult<T = unknown> {
  readonly value: T | null;
  /** Command latency in whole microseconds. Recorded even on error. */
  readonly latencyMicros: number;
  readonly error?: Error;
}
