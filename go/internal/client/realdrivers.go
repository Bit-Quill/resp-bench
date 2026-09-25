package client

import (
	"strconv"

	"github.com/resp-bench/go/internal/config"
)

// itoa is a small int→string helper shared by the client package.
func itoa(n int) string { return strconv.Itoa(n) }

// tlsEnabled reports whether a driver config requests TLS. A tls block with
// "enabled": true, or any non-empty tls block, turns TLS on.
func tlsEnabled(cfg config.DriverConfig) bool {
	if cfg.TLS == nil {
		return false
	}
	if v, ok := cfg.TLS["enabled"].(bool); ok {
		return v
	}
	return len(cfg.TLS) > 0
}
