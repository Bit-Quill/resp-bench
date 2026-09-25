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

// CreateAndConnect builds a client for the driver and connects it.
func CreateAndConnect(host string, port int, cfg config.DriverConfig) (BenchmarkClient, error) {
	c, err := New(cfg.DriverID)
	if err != nil {
		return nil, err
	}
	if err := c.Connect(host, port, cfg); err != nil {
		return nil, fmt.Errorf("connect %s: %w", cfg.DriverID, err)
	}
	return c, nil
}
