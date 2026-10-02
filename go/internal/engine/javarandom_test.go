package engine

import "testing"

// TestJavaRandomSeedZeroAnchor pins the port to java.util.Random's canonical
// output: new Random(0).nextInt(1000) for the first 10 draws. These values are
// engine-independent facts shared by the Java/Ruby/Node/PHP ports.
func TestJavaRandomSeedZeroAnchor(t *testing.T) {
	r := NewJavaRandom(0)
	want := []int{360, 948, 29, 447, 515, 53, 491, 761, 719, 854}
	for i, w := range want {
		if got := r.NextInt(1000); got != w {
			t.Fatalf("draw %d: got %d, want %d", i, got, w)
		}
	}
}

// TestJavaRandomDeterministic verifies the same seed reproduces the same stream.
func TestJavaRandomDeterministic(t *testing.T) {
	a := NewJavaRandom(12345)
	b := NewJavaRandom(12345)
	for i := 0; i < 100; i++ {
		if x, y := a.NextInt(10000), b.NextInt(10000); x != y {
			t.Fatalf("draw %d diverged: %d vs %d", i, x, y)
		}
	}
}

// TestJavaRandomSetSeedResets verifies SetSeed restarts the stream.
func TestJavaRandomSetSeed(t *testing.T) {
	r := NewJavaRandom(1)
	first := r.NextInt(1000)
	for i := 0; i < 5; i++ {
		r.NextInt(1000)
	}
	r.SetSeed(1)
	if got := r.NextInt(1000); got != first {
		t.Fatalf("after SetSeed(1): got %d, want %d", got, first)
	}
}

// TestJavaRandomPowerOfTwo exercises the power-of-two fast path.
func TestJavaRandomPowerOfTwo(t *testing.T) {
	r := NewJavaRandom(42)
	for i := 0; i < 1000; i++ {
		if v := r.NextInt(1024); v < 0 || v >= 1024 {
			t.Fatalf("value %d out of range [0,1024)", v)
		}
	}
}
