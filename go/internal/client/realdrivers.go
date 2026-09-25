package client

import (
	"errors"
	"strconv"

	"github.com/resp-bench/go/internal/config"
)

// errDriverNotWired is returned by real-driver stubs until the client library
// is added as a dependency and the calls are implemented.
var errDriverNotWired = errors.New(
	"real driver not yet wired: add the client library to go.mod and implement Connect/Get/Set/Ping " +
		"(see go/README.md 'Wiring a real driver')")

// GlideClient is a placeholder for the Valkey GLIDE Go client.
//
// To wire it: add the glide-for-valkey Go module to go.mod, hold the client in
// this struct, and implement the methods below to call it and time each call
// (mirror GoRedisClient.measure). Keep the TimedResult contract identical.
type GlideClient struct{ notWired }

// NewGlideClient returns an unconnected GLIDE client stub.
func NewGlideClient() *GlideClient { return &GlideClient{} }

// DriverVersion returns the GLIDE client version once wired.
func (c *GlideClient) DriverVersion() string { return "valkey-glide-go-unknown" }

// notWired provides the not-implemented behavior shared by real-driver stubs.
// Connect fails loudly so a benchmark against an unimplemented driver is never
// silently misreported as success.
type notWired struct{}

func (notWired) Connect(_ string, _ int, _ config.DriverConfig) error { return errDriverNotWired }
func (notWired) Get(string) TimedResult                               { return TimedResult{Err: errDriverNotWired} }
func (notWired) Set(string, []byte) TimedResult                       { return TimedResult{Err: errDriverNotWired} }
func (notWired) Ping() TimedResult                                    { return TimedResult{Err: errDriverNotWired} }
func (notWired) Close() error                                         { return nil }
func (notWired) SecondaryDriverVersion() string                       { return "" }

// itoa is a small int→string helper shared by the client package.
func itoa(n int) string { return strconv.Itoa(n) }
