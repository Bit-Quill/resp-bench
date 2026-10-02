package engine

import (
	"math/rand"
	"sync"
	"time"

	"github.com/resp-bench/go/internal/command"
)

// CommandSelector picks a command according to its configured weight, using
// cumulative weights for O(n) selection. Each selector holds its own RNG; the
// choice RNG is intentionally NOT the Java-compatible one (command selection is
// not part of cross-engine key parity).
//
// A selector is shared across a connection's pipeline_depth worker goroutines,
// so Select is guarded by a mutex (math/rand.Rand is not concurrency-safe).
type CommandSelector struct {
	commands   []command.Command
	cumulative []float64
	mu         sync.Mutex
	rng        *rand.Rand
}

// NewCommandSelector builds a selector over the given commands.
func NewCommandSelector(cmds []command.Command) *CommandSelector {
	total := 0.0
	for _, c := range cmds {
		total += c.Weight()
	}
	if total == 0 {
		total = 1
	}
	cum := make([]float64, len(cmds))
	sum := 0.0
	for i, c := range cmds {
		sum += c.Weight() / total
		cum[i] = sum
	}
	return &CommandSelector{
		commands:   cmds,
		cumulative: cum,
		rng:        rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

// Select returns a command chosen by weight. Safe for concurrent callers.
func (s *CommandSelector) Select() command.Command {
	s.mu.Lock()
	r := s.rng.Float64()
	s.mu.Unlock()
	for i, threshold := range s.cumulative {
		if r <= threshold {
			return s.commands[i]
		}
	}
	return s.commands[len(s.commands)-1]
}
