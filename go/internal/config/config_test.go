package config

import "testing"

// TestWarmupRequestsAbsentVsExplicitZero: an absent warmup_requests defaults to
// 1, while an explicit 0 disables warmup. Promoting it to *int is what lets the
// Go engine match Java/Node/PHP/Python, which apply the default only on absence.
func TestWarmupRequestsAbsentVsExplicitZero(t *testing.T) {
	absent, err := ParseWorkloadConfig([]byte(`{
	  "benchmark_profile": {"name": "W"},
	  "phases": [{"id":"P","connections":1,
	    "commands":[{"command":"get","weight":1.0}],
	    "keyspace":{"keys_count":10,"key_size_bytes":8,"key_prefix":"w:","generation_alg":"sequential_int"},
	    "completion":{"type":"requests","requests":1}}]
	}`))
	if err != nil {
		t.Fatalf("parse absent: %v", err)
	}
	if got := absent.Phases[0].EffectiveWarmupRequests(); got != 1 {
		t.Fatalf("absent warmup_requests: got %d, want default 1", got)
	}

	zero, err := ParseWorkloadConfig([]byte(`{
	  "benchmark_profile": {"name": "W"},
	  "phases": [{"id":"P","connections":1,"warmup_requests":0,
	    "commands":[{"command":"get","weight":1.0}],
	    "keyspace":{"keys_count":10,"key_size_bytes":8,"key_prefix":"w:","generation_alg":"sequential_int"},
	    "completion":{"type":"requests","requests":1}}]
	}`))
	if err != nil {
		t.Fatalf("parse zero: %v", err)
	}
	if got := zero.Phases[0].EffectiveWarmupRequests(); got != 0 {
		t.Fatalf("explicit warmup_requests=0: got %d, want 0 (disabled)", got)
	}

	explicit, err := ParseWorkloadConfig([]byte(`{
	  "benchmark_profile": {"name": "W"},
	  "phases": [{"id":"P","connections":1,"warmup_requests":5,
	    "commands":[{"command":"get","weight":1.0}],
	    "keyspace":{"keys_count":10,"key_size_bytes":8,"key_prefix":"w:","generation_alg":"sequential_int"},
	    "completion":{"type":"requests","requests":1}}]
	}`))
	if err != nil {
		t.Fatalf("parse explicit: %v", err)
	}
	if got := explicit.Phases[0].EffectiveWarmupRequests(); got != 5 {
		t.Fatalf("explicit warmup_requests=5: got %d, want 5", got)
	}
}
