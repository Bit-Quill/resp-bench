package client

import (
	"testing"

	"github.com/resp-bench/go/internal/config"
)

// TestGoRedisSocketsPerClientReflectsDepth: go-redis pools one socket per
// in-flight slot, so sockets_per_client must equal the pipeline_depth it is
// driven at (the field that makes cross-driver comparison meaningful — GLIDE
// multiplexes and stays at 1). The pool is bounded to maxInFlight in Connect, so
// SocketsPerClient() is the committed assertion of the pooling claim without
// needing a live server.
func TestGoRedisSocketsPerClientReflectsDepth(t *testing.T) {
	for _, depth := range []int{1, 4, 16} {
		c := NewGoRedisClient()
		c.SetMaxInFlight(depth)
		if got := c.SocketsPerClient(); got != depth {
			t.Fatalf("depth=%d: sockets_per_client=%d, want %d", depth, got, depth)
		}
	}
	// Depth < 1 is clamped to a single socket.
	c := NewGoRedisClient()
	c.SetMaxInFlight(0)
	if got := c.SocketsPerClient(); got != 1 {
		t.Fatalf("depth=0 clamped: sockets_per_client=%d, want 1", got)
	}
}

// TestTLSEnabled: TLS is on only for an explicit "enabled": true. An empty block,
// a block without an "enabled" key, and "enabled": false all read as off — matching
// GLIDE and every reference engine (Python bool(tls and tls.get("enabled")),
// Node tls?.enabled === true).
func TestTLSEnabled(t *testing.T) {
	cases := []struct {
		name string
		tls  map[string]any
		want bool
	}{
		{"nil block", nil, false},
		{"empty block", map[string]any{}, false},
		{"enabled false", map[string]any{"enabled": false}, false},
		{"no enabled key with cert path", map[string]any{"ca_path": "/x"}, false},
		{"enabled true", map[string]any{"enabled": true}, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg := config.DriverConfig{TLS: tc.tls}
			if got := tlsEnabled(cfg); got != tc.want {
				t.Fatalf("tlsEnabled(%v)=%v, want %v", tc.tls, got, tc.want)
			}
		})
	}
}

// TestBuildTLSConfigOff: when TLS is off, buildTLSConfig returns (nil, nil) so
// go-redis speaks plaintext — a tls:{enabled:false} block must not trigger a
// handshake against a plaintext server.
func TestBuildTLSConfigOff(t *testing.T) {
	for _, tls := range []map[string]any{nil, {}, {"enabled": false}} {
		cfg := config.DriverConfig{TLS: tls}
		got, err := buildTLSConfig(cfg)
		if err != nil {
			t.Fatalf("buildTLSConfig(%v) error: %v", tls, err)
		}
		if got != nil {
			t.Fatalf("buildTLSConfig(%v)=%v, want nil (TLS off)", tls, got)
		}
	}
}

// TestBuildTLSConfigVerifyHostname: verify_hostname:false sets InsecureSkipVerify.
func TestBuildTLSConfigVerifyHostname(t *testing.T) {
	cfg := config.DriverConfig{TLS: map[string]any{"enabled": true, "verify_hostname": false}}
	got, err := buildTLSConfig(cfg)
	if err != nil {
		t.Fatalf("buildTLSConfig error: %v", err)
	}
	if got == nil || !got.InsecureSkipVerify {
		t.Fatalf("expected InsecureSkipVerify=true, got %#v", got)
	}
}

// TestBuildTLSConfigPartialCertRejected: cert_path without key_path (or vice
// versa) is rejected rather than silently ignored, matching redis-py honouring
// the cert pair.
func TestBuildTLSConfigPartialCertRejected(t *testing.T) {
	cfg := config.DriverConfig{TLS: map[string]any{"enabled": true, "cert_path": "/only/cert"}}
	if _, err := buildTLSConfig(cfg); err == nil {
		t.Fatal("expected error for cert_path without key_path")
	}
}

// TestBuildTLSConfigBadCAPath: a ca_path that cannot be read is a hard error, not
// a silently-ignored field.
func TestBuildTLSConfigBadCAPath(t *testing.T) {
	cfg := config.DriverConfig{TLS: map[string]any{"enabled": true, "ca_path": "/nonexistent/ca.pem"}}
	if _, err := buildTLSConfig(cfg); err == nil {
		t.Fatal("expected error for unreadable ca_path")
	}
}
