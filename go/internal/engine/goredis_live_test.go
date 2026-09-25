package engine

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/resp-bench/go/internal/config"
)

// liveServer returns host,port from VALKEY_HOST/VALKEY_PORT, or skips the test.
// This keeps the live tests out of the default `go test` run (which has no
// server) while letting CI/local runs exercise the real go-redis path.
func liveServer(t *testing.T) (string, int) {
	t.Helper()
	host := os.Getenv("VALKEY_HOST")
	if host == "" {
		t.Skip("VALKEY_HOST not set; skipping live go-redis test")
	}
	port := 6379
	if p := os.Getenv("VALKEY_PORT"); p != "" {
		if n, err := strconv.Atoi(p); err == nil {
			port = n
		}
	}
	return host, port
}

// TestGoRedisLiveBenchmark runs a small real benchmark against a live server via
// the go-redis driver and asserts the phase completes with the exact request
// count and no errors. Gated on VALKEY_HOST.
func TestGoRedisLiveBenchmark(t *testing.T) {
	host, port := liveServer(t)

	const workloadJSON = `{
	  "benchmark_profile": {"name": "go-redis-live"},
	  "phases": [{
	    "id": "STEADY",
	    "connections": 4,
	    "commands": [
	      {"command": "get", "weight": 0.7},
	      {"command": "set", "weight": 0.3, "data_size_bytes": 64}
	    ],
	    "keyspace": {"keys_count": 500, "key_size_bytes": 16, "key_prefix": "gotest:", "generation_alg": "sequential_int"},
	    "completion": {"type": "requests", "requests": 2000}
	  }]
	}`

	wl, err := config.ParseWorkloadConfig([]byte(workloadJSON))
	if err != nil {
		t.Fatalf("parse workload: %v", err)
	}
	driver := config.DriverConfig{DriverID: "go-redis", Mode: "standalone", SpecificDriverConfig: map[string]any{}}
	out := filepath.Join(t.TempDir(), "live.ndjson")

	b := New(host, port, driver, wl, out, "live-test", quietLogger())
	code, err := b.Run()
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if code != 0 {
		t.Fatalf("expected exit code 0, got %d", code)
	}

	data, err := os.ReadFile(out)
	if err != nil {
		t.Fatalf("read metrics: %v", err)
	}
	var rec map[string]any
	if err := json.Unmarshal([]byte(strings.TrimSpace(string(data))), &rec); err != nil {
		t.Fatalf("parse ndjson: %v", err)
	}
	phase := rec["phase"].(map[string]any)
	if phase["status"] != StatusCompleted {
		t.Fatalf("status=%v, want COMPLETED", phase["status"])
	}
	totals := rec["totals"].(map[string]any)
	if got := int(totals["requests"].(float64)); got != 2000 {
		t.Fatalf("requests=%d, want 2000", got)
	}
	if errs := int(totals["errors"].(float64)); errs != 0 {
		t.Fatalf("errors=%d, want 0", errs)
	}
	metrics := rec["metrics"].(map[string]any)
	if _, ok := metrics["GET"]; !ok {
		t.Fatal("expected GET metrics from live run")
	}
}

// TestGoRedisConnectFailure verifies connecting to a dead port fails loudly
// (the phase is ERROR, exit non-zero) rather than silently reporting success.
func TestGoRedisConnectFailure(t *testing.T) {
	wl, _ := config.ParseWorkloadConfig([]byte(`{
	  "benchmark_profile": {"name": "dead"},
	  "phases": [{"id":"P","connections":2,
	    "commands":[{"command":"get","weight":1.0}],
	    "keyspace":{"keys_count":10,"key_size_bytes":16,"key_prefix":"x:"},
	    "completion":{"type":"requests","requests":10}}]
	}`))
	driver := config.DriverConfig{DriverID: "go-redis", Mode: "standalone"}
	out := filepath.Join(t.TempDir(), "dead.ndjson")
	// Port 1 is not a listening Redis; connect must fail.
	b := New("127.0.0.1", 1, driver, wl, out, "t", quietLogger())
	code, _ := b.Run()
	if code == 0 {
		t.Fatal("expected non-zero exit when the server is unreachable")
	}
	data, _ := os.ReadFile(out)
	if !strings.Contains(string(data), `"status":"ERROR"`) {
		t.Fatalf("expected ERROR status, got: %s", data)
	}
}
