package engine

import (
	"encoding/json"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/resp-bench/go/internal/config"
)

func quietLogger() *log.Logger { return log.New(io.Discard, "", 0) }

func recordingDriver() config.DriverConfig {
	return config.DriverConfig{DriverID: "recording", Mode: "standalone", SpecificDriverConfig: map[string]any{}}
}

// runPhases runs the engine against the recording driver and returns the parsed
// NDJSON phase records.
func runPhases(t *testing.T, workloadJSON string, connections int) []map[string]any {
	t.Helper()
	wl, err := config.ParseWorkloadConfig([]byte(workloadJSON))
	if err != nil {
		t.Fatalf("parse workload: %v", err)
	}
	out := filepath.Join(t.TempDir(), "metrics.ndjson")
	b := New("localhost", 6379, recordingDriver(), wl, out, "test", quietLogger())
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
	var recs []map[string]any
	for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		if line == "" {
			continue
		}
		var m map[string]any
		if err := json.Unmarshal([]byte(line), &m); err != nil {
			t.Fatalf("parse ndjson line: %v", err)
		}
		recs = append(recs, m)
	}
	return recs
}

const requestWorkload = `{
  "benchmark_profile": {"name": "IT"},
  "phases": [{
    "id": "STEADY",
    "connections": %d,
    "commands": [
      {"command": "get", "weight": 0.8},
      {"command": "set", "weight": 0.2, "data_size_bytes": 32}
    ],
    "keyspace": {"keys_count": 100, "key_size_bytes": 16, "key_prefix": "b:", "generation_alg": "sequential_int"},
    "completion": {"type": "requests", "requests": 500}
  }]
}`

// TestEngineRequestBasedCompletion: the shared budget yields exactly the target
// request count regardless of connection count.
func TestEngineRequestBasedCompletion(t *testing.T) {
	for _, conns := range []int{1, 4} {
		wl := strings.Replace(requestWorkload, "%d", itoa(conns), 1)
		recs := runPhases(t, wl, conns)
		if len(recs) != 1 {
			t.Fatalf("conns=%d: expected 1 phase, got %d", conns, len(recs))
		}
		phase := recs[0]["phase"].(map[string]any)
		if phase["status"] != StatusCompleted {
			t.Fatalf("conns=%d: status=%v", conns, phase["status"])
		}
		totals := recs[0]["totals"].(map[string]any)
		if got := int(totals["requests"].(float64)); got != 500 {
			t.Fatalf("conns=%d: requests=%d, want 500", conns, got)
		}
	}
}

// TestEngineMetricsSerializeAsObject: an empty metrics map must be {}, not [].
func TestEngineMetricsIsObject(t *testing.T) {
	recs := runPhases(t, strings.Replace(requestWorkload, "%d", "1", 1), 1)
	// metrics must be a JSON object with GET/SET keys.
	metrics, ok := recs[0]["metrics"].(map[string]any)
	if !ok {
		t.Fatalf("metrics is not an object: %T", recs[0]["metrics"])
	}
	if _, ok := metrics["GET"]; !ok {
		t.Fatalf("expected GET in metrics, got keys %v", keys(metrics))
	}
}

// TestRejectPipelineDepth: unsupported knobs must fail loudly.
func TestRejectPipelineDepth(t *testing.T) {
	wl, err := config.ParseWorkloadConfig([]byte(`{
	  "benchmark_profile": {"name": "PD"},
	  "phases": [{"id":"P","connections":1,"pipeline_depth":8,
	    "commands":[{"command":"get","weight":1.0}],
	    "keyspace":{"keys_count":10},"completion":{"type":"requests","requests":10}}]
	}`))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	out := filepath.Join(t.TempDir(), "m.ndjson")
	b := New("localhost", 6379, recordingDriver(), wl, out, "t", quietLogger())
	code, err := b.Run()
	if err == nil {
		t.Fatal("expected error for pipeline_depth > 1")
	}
	if code == 0 {
		t.Fatal("expected non-zero exit code for unsupported knob")
	}
	if !strings.Contains(err.Error(), "pipeline_depth") {
		t.Fatalf("error should mention pipeline_depth: %v", err)
	}
}

// TestUnknownDriverFailsLoudly: a real-driver stub must not report success.
func TestUnknownDriverFailsLoudly(t *testing.T) {
	wl, _ := config.ParseWorkloadConfig([]byte(strings.Replace(requestWorkload, "%d", "2", 1)))
	out := filepath.Join(t.TempDir(), "m.ndjson")
	driver := config.DriverConfig{DriverID: "valkey-glide-go", Mode: "standalone"}
	b := New("localhost", 6379, driver, wl, out, "t", quietLogger())
	code, _ := b.Run()
	if code == 0 {
		t.Fatal("expected non-zero exit code when the driver cannot connect")
	}
	// The phase should be recorded as ERROR.
	data, _ := os.ReadFile(out)
	if !strings.Contains(string(data), `"status":"ERROR"`) {
		t.Fatalf("expected ERROR status in output, got: %s", data)
	}
}

// --- tiny helpers to avoid importing strconv/sort in the test ---

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	if neg {
		b = append([]byte{'-'}, b...)
	}
	return string(b)
}

func keys(m map[string]any) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}
