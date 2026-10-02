// Package command implements the benchmark commands (GET/SET/PING) and their
// weighted selection.
package command

import (
	"fmt"

	"github.com/resp-bench/go/internal/client"
	"github.com/resp-bench/go/internal/config"
)

// Result is the outcome of executing a command, ready for the collector.
type Result struct {
	Name          string
	LatencyMicros int64
	Success       bool
}

// KeySource yields the next key for a command. PING ignores it; GET/SET consume
// exactly one key per execution (matching the reference engines).
type KeySource interface {
	NextKey() string
}

// Command is a single executable benchmark operation.
type Command interface {
	// Name is the uppercase command name used as the metrics key (GET/SET/PING).
	Name() string
	// Weight is the relative selection weight.
	Weight() float64
	// Execute runs the command against the client and returns a timed Result.
	Execute(c client.BenchmarkClient, keys KeySource) Result
}

// base holds the shared name/weight for all commands.
type base struct {
	name   string
	weight float64
}

func (b base) Name() string    { return b.name }
func (b base) Weight() float64 { return b.weight }

// getCommand executes GET, consuming one key.
type getCommand struct{ base }

func (g getCommand) Execute(c client.BenchmarkClient, keys KeySource) Result {
	r := c.Get(keys.NextKey())
	return Result{Name: g.name, LatencyMicros: r.LatencyMicros, Success: r.Success()}
}

// setCommand executes SET with a precomputed deterministic value, consuming one key.
type setCommand struct {
	base
	value []byte
}

func (s setCommand) Execute(c client.BenchmarkClient, keys KeySource) Result {
	r := c.Set(keys.NextKey(), s.value)
	return Result{Name: s.name, LatencyMicros: r.LatencyMicros, Success: r.Success()}
}

// pingCommand executes PING. It deliberately does NOT consume a key — consuming
// one would shift every subsequent key and break cross-engine parity.
type pingCommand struct{ base }

func (p pingCommand) Execute(c client.BenchmarkClient, _ KeySource) Result {
	r := c.Ping()
	return Result{Name: p.name, LatencyMicros: r.LatencyMicros, Success: r.Success()}
}

// New builds a single command from its config.
func New(cfg config.CommandConfig) (Command, error) {
	switch cfg.Command {
	case "get":
		return getCommand{base{name: "GET", weight: cfg.Weight}}, nil
	case "set":
		return setCommand{base{name: "SET", weight: cfg.Weight}, generateValue(cfg.DataSizeBytes)}, nil
	case "ping":
		return pingCommand{base{name: "PING", weight: cfg.Weight}}, nil
	default:
		return nil, fmt.Errorf("unknown command %q (supported: get, set, ping)", cfg.Command)
	}
}

// NewAll builds all commands from their configs.
func NewAll(cfgs []config.CommandConfig) ([]Command, error) {
	cmds := make([]Command, 0, len(cfgs))
	for _, c := range cfgs {
		cmd, err := New(c)
		if err != nil {
			return nil, err
		}
		cmds = append(cmds, cmd)
	}
	return cmds, nil
}

// generateValue builds a deterministic value of the requested byte size, using
// the same repeating hex pattern as the Ruby/PHP engines.
func generateValue(size int) []byte {
	if size <= 0 {
		return []byte{}
	}
	const pattern = "0123456789ABCDEF"
	out := make([]byte, size)
	for i := 0; i < size; i++ {
		out[i] = pattern[i%len(pattern)]
	}
	return out
}
