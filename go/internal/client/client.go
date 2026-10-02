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
//
// This engine creates one client instance per connection. At pipeline_depth=1 a
// connection keeps one command in flight; above that the engine keeps
// pipeline_depth of them in flight on the same client, so implementations must
// tolerate concurrent calls.
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

// PipelineAware is optionally implemented by drivers that need to know the
// pipeline depth they will be driven at (e.g. a pooling driver bounds its pool).
// The factory calls SetMaxInFlight before Connect and Prime right after.
type PipelineAware interface {
	// SetMaxInFlight declares how many commands the engine will keep in flight
	// on this client (the phase's pipeline_depth).
	SetMaxInFlight(depth int)
	// Prime opens everything the depth implies before the measured window, so a
	// pooling driver does not pay socket setup inside the phase. Multiplexing
	// drivers can no-op.
	Prime() error
}

// DetailedClient is optionally implemented by drivers that expose
// environment-dependent settings worth recording in the metrics metadata
// (negotiated RESP protocol, response parser, retry count, pipelining mode).
type DetailedClient interface {
	// SocketsPerClient reports how many server connections the client holds:
	// 1 for a multiplexing driver at any depth, pipeline_depth for a pooling one.
	SocketsPerClient() int
	// DriverDetails returns metadata key/values to merge into the output.
	DriverDetails() map[string]any
}
