package client

import (
	"fmt"

	"github.com/resp-bench/go/internal/config"
)

// SupportedDrivers lists the driver_ids this engine can construct.
var SupportedDrivers = []string{
	"recording",
	"valkey-glide-go",
	"go-redis",
}

// New constructs an unconnected client for the given driver_id.
func New(driverID string) (BenchmarkClient, error) {
	switch driverID {
	case "recording":
		return NewRecordingClient(), nil
	case "valkey-glide-go":
		return NewGlideClient(), nil
	case "go-redis", "redis-go", "goredis":
		return NewGoRedisClient(), nil
	default:
		return nil, fmt.Errorf("unknown driver_id %q (supported: %v)", driverID, SupportedDrivers)
	}
}

// CreateAndConnect builds a client for the driver, declares the pipeline depth,
// connects it, and primes any pooled sockets before the measured window.
func CreateAndConnect(host string, port int, cfg config.DriverConfig, pipelineDepth int) (BenchmarkClient, error) {
	c, err := New(cfg.DriverID)
	if err != nil {
		return nil, err
	}
	if pa, ok := c.(PipelineAware); ok {
		pa.SetMaxInFlight(pipelineDepth)
	}
	if err := c.Connect(host, port, cfg); err != nil {
		return nil, fmt.Errorf("connect %s: %w", cfg.DriverID, err)
	}
	if pa, ok := c.(PipelineAware); ok {
		if err := pa.Prime(); err != nil {
			_ = c.Close()
			return nil, fmt.Errorf("prime %s: %w", cfg.DriverID, err)
		}
	}
	return c, nil
}

// SocketsPerClient reports the server-connection count a client holds, defaulting
// to 1 for drivers that do not implement DetailedClient.
func SocketsPerClient(c BenchmarkClient) int {
	if d, ok := c.(DetailedClient); ok {
		if n := d.SocketsPerClient(); n > 0 {
			return n
		}
	}
	return 1
}

// DriverDetails returns a client's extra metadata, or nil if it exposes none.
func DriverDetails(c BenchmarkClient) map[string]any {
	if d, ok := c.(DetailedClient); ok {
		return d.DriverDetails()
	}
	return nil
}
