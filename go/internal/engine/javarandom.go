// Package engine contains the workload driving logic: RNG, key generation,
// rate limiting, command selection, and the benchmark orchestrator.
package engine

// JavaRandom is a port of java.util.Random's 48-bit linear congruential
// generator, producing identical sequences to the Java/Ruby/Node/PHP engines.
//
// Go's int64 holds the 48-bit multiply exactly, so — unlike the JavaScript port
// which needs BigInt — plain integer arithmetic with masking is sufficient.
type JavaRandom struct {
	seed int64
}

const (
	jrMultiplier = 0x5DEECE66D
	jrAddend     = 0xB
	jrMask       = (int64(1) << 48) - 1
)

// NewJavaRandom creates a generator seeded like Java's Random(long seed).
func NewJavaRandom(seed int64) *JavaRandom {
	return &JavaRandom{seed: (seed ^ jrMultiplier) & jrMask}
}

// SetSeed re-seeds the generator (matches Java's setSeed).
func (r *JavaRandom) SetSeed(seed int64) {
	r.seed = (seed ^ jrMultiplier) & jrMask
}

// next returns the next `bits` high bits, matching Java's protected next(int).
// The result is returned as a signed 32-bit value (Java's int), so callers can
// reproduce Java's signed arithmetic in nextInt's rejection branch.
func (r *JavaRandom) next(bits uint) int32 {
	r.seed = (r.seed*jrMultiplier + jrAddend) & jrMask
	return int32(r.seed >> (48 - bits))
}

// NextInt returns a uniformly distributed value in [0, bound), matching Java's
// Random.nextInt(int bound), including the modulo-bias rejection loop.
func (r *JavaRandom) NextInt(bound int) int {
	if bound <= 0 {
		panic("bound must be positive")
	}

	// Power-of-two fast path: (bound * next(31)) >> 31.
	if bound&(-bound) == bound {
		return int((int64(bound) * int64(r.next(31))) >> 31)
	}

	// General case with rejection sampling to avoid modulo bias. The rejection
	// test must be done in signed 32-bit arithmetic exactly as Java does it:
	//   while (bits - val + (bound-1) < 0) retry;
	// The subexpression can overflow int32, and that overflow is what triggers a
	// rejection — reproducing it (rather than using wider ints) is required for
	// bit-exact parity.
	for {
		bits := r.next(31)
		val := bits % int32(bound)
		if bits-val+int32(bound-1) >= 0 {
			return int(val)
		}
	}
}
