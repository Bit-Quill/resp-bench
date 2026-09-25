package engine

import (
	"fmt"
	"sync"
	"sync/atomic"

	"github.com/resp-bench/go/internal/config"
)

// KeyGenerator produces keys identical to the reference engines.
//
// Two algorithms:
//   - sequential_int: indices 0,1,2,… from a counter SHARED across all workers,
//     wrapping at keys_count. A shared atomic counter guarantees the union of
//     all workers covers the keyspace exactly once per pass with no duplication.
//   - uniform_rand: a PER-WORKER JavaRandom seeded base_seed+worker_index, so
//     each worker is independent yet reproducible.
//
// Keys are zero-padded to key_size_bytes: "%0Nd" where N = max(1, key_size_bytes
// - len(prefix)), matching Java's formatting.
type KeyGenerator struct {
	prefix       string
	keySizeBytes int
	keysCount    int
	sequential   bool

	// sequential_int: shared counter (nil for uniform_rand generators).
	counter *atomic.Int64

	// uniform_rand: per-worker PRNG (nil for sequential generators).
	rng *JavaRandom
	mu  sync.Mutex
}

// KeyGenSet holds the shared state for a phase's key generation so that all
// per-worker generators created via ForWorker share one sequential counter.
type KeyGenSet struct {
	cfg     config.KeyspaceConfig
	counter *atomic.Int64
}

// NewKeyGenSet builds the shared key-generation state for a phase.
func NewKeyGenSet(cfg config.KeyspaceConfig) *KeyGenSet {
	return &KeyGenSet{cfg: cfg, counter: &atomic.Int64{}}
}

// ForWorker returns a KeyGenerator for a specific worker. Sequential generators
// share the set's atomic counter; random generators get their own PRNG seeded
// base_seed + workerIndex.
func (s *KeyGenSet) ForWorker(workerIndex int) *KeyGenerator {
	kg := &KeyGenerator{
		prefix:       s.cfg.EffectiveKeyPrefix(),
		keySizeBytes: s.cfg.KeySizeBytes,
		keysCount:    s.cfg.KeysCount,
		sequential:   s.cfg.IsSequentialInt(),
	}
	if kg.sequential {
		kg.counter = s.counter
	} else {
		kg.rng = NewJavaRandom(s.cfg.SeedValue() + int64(workerIndex))
	}
	return kg
}

// keysCountOrOne guards against a zero keys_count causing a divide-by-zero.
func (k *KeyGenerator) keysCountOrOne() int {
	if k.keysCount <= 0 {
		return 1
	}
	return k.keysCount
}

// NextKey returns the next key as a string.
func (k *KeyGenerator) NextKey() string {
	var idx int
	if k.sequential {
		// fetch-and-increment, then wrap.
		n := k.counter.Add(1) - 1
		idx = int(n % int64(k.keysCountOrOne()))
	} else {
		k.mu.Lock()
		idx = k.rng.NextInt(k.keysCountOrOne())
		k.mu.Unlock()
	}
	return k.formatKey(idx)
}

// formatKey zero-pads the index to fill key_size_bytes after the prefix.
func (k *KeyGenerator) formatKey(idx int) string {
	pad := k.keySizeBytes - len(k.prefix)
	if pad < 1 {
		pad = 1
	}
	return fmt.Sprintf("%s%0*d", k.prefix, pad, idx)
}
