package client

import (
	"strconv"

	"github.com/resp-bench/go/internal/config"
)

// itoa is a small int→string helper shared by the client package.
func itoa(n int) string { return strconv.Itoa(n) }

// tlsEnabled reports whether a driver config requests TLS. TLS is on only when
// the tls block carries "enabled": true, matching the reference engines
// (Python bool(tls and tls.get("enabled")), Node tls?.enabled === true). A block
// present without an explicit "enabled" key is treated as off.
func tlsEnabled(cfg config.DriverConfig) bool {
	if cfg.TLS == nil {
		return false
	}
	enabled, _ := cfg.TLS["enabled"].(bool)
	return enabled
}
