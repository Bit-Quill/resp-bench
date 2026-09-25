package client

import (
	"errors"
	"math/rand"
	"sync"
	"time"

	"github.com/resp-bench/go/internal/config"
)

// RecordingClient is an in-memory, server-free driver used for tests and
// pipeline validation. It exercises the full engine path (key generation,
// command selection, metrics, NDJSON output) without a live server.
//
// Latency is a small synthetic value. Optional error simulation and operation
// logging are configured via specific_driver_config, matching the reference
// engines:
//
//	"specific_driver_config": {
//	    "error_rate": 0.1,
//	    "error_message": "...",
//	    "record_operations": true
//	}
type RecordingClient struct {
	mu        sync.Mutex
	store     map[string]string
	connected bool

	// Operation logging is OFF by default: in a real benchmark the log would
	// grow by one entry per request with nothing draining it, exhausting memory
	// on long/duration-based phases. Tests opt in via record_operations=true.
	recordOps bool
	recorded  []recordedOp

	errorRate    float64
	errorMessage string
	rng          *rand.Rand
}

type recordedOp struct {
	Op   string
	Key  string
	Size int
}

// NewRecordingClient returns an unconnected recording client.
func NewRecordingClient() *RecordingClient {
	return &RecordingClient{
		store:        map[string]string{},
		errorMessage: "Simulated error",
		rng:          rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

// Connect marks the client connected and reads optional simulation knobs.
func (c *RecordingClient) Connect(_ string, _ int, cfg config.DriverConfig) error {
	c.connected = true
	sdc := cfg.SpecificDriverConfig
	if sdc == nil {
		return nil
	}
	if v, ok := sdc["error_rate"].(float64); ok {
		if v < 0 {
			v = 0
		}
		if v > 1 {
			v = 1
		}
		c.errorRate = v
	}
	if v, ok := sdc["error_message"].(string); ok {
		c.errorMessage = v
	}
	if v, ok := sdc["record_operations"].(bool); ok {
		c.recordOps = v
	}
	return nil
}

func (c *RecordingClient) record(op, key string, size int) {
	if c.recordOps {
		c.recorded = append(c.recorded, recordedOp{Op: op, Key: key, Size: size})
	}
}

func (c *RecordingClient) shouldFail() bool {
	if c.errorRate <= 0 {
		return false
	}
	if c.errorRate >= 1 {
		return true
	}
	return c.rng.Float64() < c.errorRate
}

// measure runs fn, timing it, and packages the outcome as a TimedResult.
func (c *RecordingClient) measure(fn func() (any, error)) TimedResult {
	start := time.Now()
	val, err := fn()
	lat := time.Since(start).Microseconds()
	if lat < 1 {
		lat = 1 // synthetic floor so the histogram (min value 1) always records
	}
	return TimedResult{Value: val, LatencyMicros: lat, Err: err}
}

// Ping simulates a PING.
func (c *RecordingClient) Ping() TimedResult {
	return c.measure(func() (any, error) {
		c.mu.Lock()
		defer c.mu.Unlock()
		c.record("PING", "", 0)
		if c.shouldFail() {
			return nil, errors.New(c.errorMessage)
		}
		return "PONG", nil
	})
}

// Get simulates a GET.
func (c *RecordingClient) Get(key string) TimedResult {
	return c.measure(func() (any, error) {
		c.mu.Lock()
		defer c.mu.Unlock()
		c.record("GET", key, 0)
		if c.shouldFail() {
			return nil, errors.New(c.errorMessage)
		}
		if v, ok := c.store[key]; ok {
			return v, nil
		}
		return nil, nil
	})
}

// Set simulates a SET.
func (c *RecordingClient) Set(key string, value []byte) TimedResult {
	return c.measure(func() (any, error) {
		c.mu.Lock()
		defer c.mu.Unlock()
		c.record("SET", key, len(value))
		if c.shouldFail() {
			return nil, errors.New(c.errorMessage)
		}
		c.store[key] = string(value)
		return "OK", nil
	})
}

// Close marks the client disconnected.
func (c *RecordingClient) Close() error {
	c.connected = false
	return nil
}

// DriverVersion returns a synthetic version string.
func (c *RecordingClient) DriverVersion() string { return "recording-1.0" }

// SecondaryDriverVersion is unused for the recording client.
func (c *RecordingClient) SecondaryDriverVersion() string { return "" }

// RecordedOperations returns the logged operations (empty unless record_operations).
func (c *RecordingClient) RecordedOperations() []recordedOp { return c.recorded }
