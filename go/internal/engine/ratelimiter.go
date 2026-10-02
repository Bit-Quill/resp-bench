package engine

import (
	"sync"
	"time"
)

// RateLimiter enforces a constant rate with no burst capacity, spacing
// operations evenly (e.g. rps=20 → one op every 50ms). Matches the leaky-bucket
// behavior of the Java/Ruby reference implementations.
//
// A nil *RateLimiter is a valid no-op (unlimited); callers may hold nil and call
// Acquire on it safely.
type RateLimiter struct {
	intervalNanos int64
	mu            sync.Mutex
	nextAllowed   int64 // monotonic nanoseconds
}

// NewRateLimiter returns a limiter for rate>0, or nil for unlimited (rate<=0).
func NewRateLimiter(ratePerSecond int) *RateLimiter {
	if ratePerSecond <= 0 {
		return nil
	}
	return &RateLimiter{
		intervalNanos: int64(time.Second) / int64(ratePerSecond),
		nextAllowed:   nowNanos(),
	}
}

// Acquire blocks until the next operation is permitted. Safe on a nil receiver.
func (r *RateLimiter) Acquire() {
	if r == nil {
		return
	}
	for {
		r.mu.Lock()
		now := nowNanos()
		if now >= r.nextAllowed {
			r.nextAllowed += r.intervalNanos
			r.mu.Unlock()
			return
		}
		wait := r.nextAllowed - now
		r.mu.Unlock()
		time.Sleep(time.Duration(wait))
	}
}

// nowNanos returns a monotonic clock reading in nanoseconds.
func nowNanos() int64 {
	return time.Now().UnixNano()
}
