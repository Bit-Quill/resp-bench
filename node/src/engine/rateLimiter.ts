/**
 * Leaky-bucket rate limiter.
 *
 * Enforces a constant rate with no burst (evenly-spaced operations), matching
 * the Java reference's interval math exactly:
 * `intervalNanos = 1_000_000_000 / ratePerSecond` (RateLimiter.java:28). Despite
 * what docs/ARCHITECTURE.md says about a "token bucket", every engine actually
 * implements this leaky bucket, so this follows the code rather than the doc.
 *
 * A single limiter is shared across all of a phase's workers. Because JS is
 * single-threaded, the check-and-advance of `nextAllowedNanos` has no `await`
 * between read and write, so it is atomic -- no CAS loop is needed (Java needs
 * one only because its issuers are real threads).
 *
 * Node-specific wrinkle: `setTimeout` clamps to ~1ms, so at high rates (a 100k
 * rps limit is a 10us interval) a timer-based wait would undershoot the target
 * badly. Sub-millisecond waits therefore yield via `setImmediate`, which returns
 * to the event loop -- letting other connections progress -- without sleeping a
 * whole millisecond.
 */

const NANOS_PER_SECOND = 1_000_000_000n;
const NANOS_PER_MILLI = 1_000_000n;

/** Yield to the event loop without a clamped timer delay. */
function yieldToEventLoop(): Promise<void> {
  return new Promise((resolve) => setImmediate(resolve));
}

function sleepMillis(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis));
}

export class RateLimiter {
  readonly ratePerSecond: number;
  private readonly intervalNanos: bigint;
  private nextAllowedNanos: bigint;

  private constructor(ratePerSecond: number) {
    this.ratePerSecond = ratePerSecond;
    this.intervalNanos = NANOS_PER_SECOND / BigInt(ratePerSecond);
    // The first operation is allowed immediately.
    this.nextAllowedNanos = process.hrtime.bigint();
  }

  /** Return a limiter, or null for unlimited (rate <= 0). */
  static create(ratePerSecond: number): RateLimiter | null {
    return ratePerSecond > 0 ? new RateLimiter(ratePerSecond) : null;
  }

  async acquire(): Promise<void> {
    for (;;) {
      const now = process.hrtime.bigint();
      if (now >= this.nextAllowedNanos) {
        this.nextAllowedNanos += this.intervalNanos;
        return;
      }
      const waitNanos = this.nextAllowedNanos - now;
      if (waitNanos >= NANOS_PER_MILLI) {
        await sleepMillis(Number(waitNanos / NANOS_PER_MILLI));
      } else {
        await yieldToEventLoop();
      }
    }
  }
}
