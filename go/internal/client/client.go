// Package client defines the common benchmark client interface and its drivers.
package client

import "github.com/resp-bench/go/internal/config"

// TimedResult is the outcome of a single command execution.
type TimedResult struct {
	// Value holds the command's return value (may be nil for SET/PING).
	Value any
	// LatencyMicros is the wall-clock latency of the call in microseconds.
	LatencyMicros int64
	// Err is non-nil if the command failed.
	Err error
}

// Success reports whether the command completed without error.
func (r TimedResult) Success() bool { return r.Err == nil }

// BenchmarkClient is the interface every driver implements. Implementations
// time each call themselves and return the latency in the result.
type BenchmarkClient interface {
	// Connect establishes the connection(s) to the server.
	Connect(host string, port int, cfg config.DriverConfig) error
	// Get executes GET and returns the timed result.
	Get(key string) TimedResult
	// Set executes SET key=value and returns the timed result.
	Set(key string, value []byte) TimedResult
	// Ping executes PING and returns the timed result.
	Ping() TimedResult
	// Close releases the connection.
	Close() error
	// DriverVersion returns the client library version (for metadata).
	DriverVersion() string
	// SecondaryDriverVersion returns a secondary/underlying client version, if any.
	SecondaryDriverVersion() string
}
