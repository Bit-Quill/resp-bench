package engine

import (
	"testing"

	"github.com/resp-bench/go/internal/config"
)

func seq(count, size int, prefix string) config.KeyspaceConfig {
	return config.KeyspaceConfig{
		KeysCount:     count,
		KeySizeBytes:  size,
		KeyPrefix:     prefix,
		GenerationAlg: "sequential_int",
	}
}

// TestSequentialWrapsAround: a single worker emits 0..N-1 then wraps.
func TestSequentialWrapsAround(t *testing.T) {
	// prefix "test:" (5) + key_size 16 → pad width = 16-5 = 11 → "%011d".
	set := NewKeyGenSet(seq(3, 16, "test:"))
	kg := set.ForWorker(0)
	want := []string{"test:00000000000", "test:00000000001", "test:00000000002", "test:00000000000"}
	for i, w := range want {
		if got := kg.NextKey(); got != w {
			t.Fatalf("key %d: got %q, want %q", i, got, w)
		}
	}
}

// TestSequentialPartitionAcrossWorkers: N workers share one counter, so their
// combined output covers the keyspace exactly once with no duplicates.
func TestSequentialPartitionAcrossWorkers(t *testing.T) {
	const workers = 4
	const keys = 5
	set := NewKeyGenSet(seq(keys*workers, 16, "k:"))

	gens := make([]*KeyGenerator, workers)
	for i := range gens {
		gens[i] = set.ForWorker(i)
	}

	seen := map[string]bool{}
	// Each worker pulls `keys` keys; total = workers*keys distinct keys.
	for i := 0; i < keys; i++ {
		for w := 0; w < workers; w++ {
			k := gens[w].NextKey()
			if seen[k] {
				t.Fatalf("duplicate key %q across workers", k)
			}
			seen[k] = true
		}
	}
	if len(seen) != workers*keys {
		t.Fatalf("expected %d distinct keys, got %d", workers*keys, len(seen))
	}
}

// TestUniformRandReproducible: same seed + worker index → same key stream.
func TestUniformRandReproducible(t *testing.T) {
	seed := int64(12345)
	cfg := config.KeyspaceConfig{
		KeysCount:     1000,
		KeySizeBytes:  16,
		KeyPrefix:     "r:",
		GenerationAlg: "uniform_rand",
		Seed:          &seed,
	}
	a := NewKeyGenSet(cfg).ForWorker(0)
	b := NewKeyGenSet(cfg).ForWorker(0)
	for i := 0; i < 100; i++ {
		if x, y := a.NextKey(), b.NextKey(); x != y {
			t.Fatalf("key %d diverged: %q vs %q", i, x, y)
		}
	}
}

// TestUniformRandSeedZeroAnchor: with seed 0 and 1000 keys, the first index is
// JavaRandom(0).nextInt(1000) == 360 (verified against the canonical Java LCG).
func TestUniformRandSeedZeroAnchor(t *testing.T) {
	seed := int64(0)
	cfg := config.KeyspaceConfig{
		KeysCount:     1000,
		KeySizeBytes:  8,
		KeyPrefix:     "k:",
		GenerationAlg: "uniform_rand",
		Seed:          &seed,
	}
	kg := NewKeyGenSet(cfg).ForWorker(0)
	// prefix "k:" (2) + key_size 8 → pad width = 6 → "%06d"; index 360 → "000360".
	if got := kg.NextKey(); got != "k:000360" {
		t.Fatalf("first uniform_rand key: got %q, want %q", got, "k:000360")
	}
}
