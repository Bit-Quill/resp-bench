//! Leaky-bucket rate limiter.
//!
//! Enforces a constant rate with no burst (evenly-spaced operations), matching
//! the Java reference's interval math: `interval_ns = 1_000_000_000 / rate`. A
//! single limiter is shared across a phase's worker threads; `acquire` blocks
//! the calling thread until its slot is due.

use std::sync::atomic::{AtomicI64, Ordering};
use std::time::{Duration, Instant};

/// A shared, thread-safe evenly-spaced rate limiter.
pub struct RateLimiter {
    interval_nanos: i64,
    next_allowed_nanos: AtomicI64,
    origin: Instant,
}

impl RateLimiter {
    /// Create a limiter, or `None` for unlimited (rate <= 0).
    pub fn create(rate_per_second: i64) -> Option<RateLimiter> {
        if rate_per_second <= 0 {
            return None;
        }
        Some(RateLimiter {
            interval_nanos: 1_000_000_000 / rate_per_second,
            next_allowed_nanos: AtomicI64::new(0),
            origin: Instant::now(),
        })
    }

    fn now_nanos(&self) -> i64 {
        self.origin.elapsed().as_nanos() as i64
    }

    /// Block until this caller's slot is due, then reserve the next slot.
    pub fn acquire(&self) {
        loop {
            let now = self.now_nanos();
            let next = self.next_allowed_nanos.load(Ordering::Acquire);
            let slot = next.max(now);
            // Reserve `slot` by advancing the shared cursor to slot + interval.
            if self
                .next_allowed_nanos
                .compare_exchange(
                    next,
                    slot + self.interval_nanos,
                    Ordering::AcqRel,
                    Ordering::Acquire,
                )
                .is_ok()
            {
                let wait = slot - now;
                if wait > 0 {
                    std::thread::sleep(Duration::from_nanos(wait as u64));
                }
                return;
            }
            // Lost the race; retry with the updated cursor.
        }
    }
}

/// Acquire on an optional limiter (no-op when unlimited).
pub fn acquire(limiter: &Option<RateLimiter>) {
    if let Some(l) = limiter {
        l.acquire();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unlimited_returns_none() {
        assert!(RateLimiter::create(0).is_none());
        assert!(RateLimiter::create(-1).is_none());
    }

    #[test]
    fn enforces_approximate_rate() {
        // 1000 rps → ~1ms spacing; 20 acquires should take at least ~15ms.
        let limiter = RateLimiter::create(1000).unwrap();
        let start = Instant::now();
        for _ in 0..20 {
            limiter.acquire();
        }
        assert!(start.elapsed() >= Duration::from_millis(15));
    }
}
