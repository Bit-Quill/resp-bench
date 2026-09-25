package metrics

import (
	"sync"
	"time"

	"github.com/resp-bench/go/internal/command"
)

// maxTrackableMicros clamps outliers to the histogram's ceiling so a stray huge
// latency cannot overflow the trackable range (matches the reference engines).
const maxTrackableMicros = 600_000_000

// CommandMetrics holds per-command counts and a latency histogram.
type CommandMetrics struct {
	Name      string
	Requests  int64
	Errors    int64
	Histogram *HdrHistogram
}

func newCommandMetrics(name string) *CommandMetrics {
	return &CommandMetrics{Name: name, Histogram: NewDefaultHdrHistogram()}
}

func (m *CommandMetrics) record(r command.Result) {
	m.Requests++
	if r.Success {
		lat := r.LatencyMicros
		if lat > maxTrackableMicros {
			lat = maxTrackableMicros
		}
		m.Histogram.Record(lat)
	} else {
		m.Errors++
	}
}

func (m *CommandMetrics) mergeFrom(other *CommandMetrics) {
	m.Requests += other.Requests
	m.Errors += other.Errors
	if other.Histogram != nil && other.Histogram.TotalCount() > 0 {
		m.Histogram.Merge(other.Histogram)
	}
}

// Collector is a lock-free per-worker collector. Each worker gets its own via
// NewCollector; results are combined with MergeFrom after workers finish.
type Collector struct {
	commands      map[string]*CommandMetrics
	totalRequests int64
	totalErrors   int64
	start         time.Time
	stop          time.Time
	started       bool
	stopped       bool
}

// NewCollector returns an empty collector.
func NewCollector() *Collector {
	return &Collector{commands: map[string]*CommandMetrics{}}
}

// Start marks the beginning of the measured window.
func (c *Collector) Start() {
	c.start = time.Now()
	c.started = true
}

// Stop marks the end of the measured window.
func (c *Collector) Stop() {
	c.stop = time.Now()
	c.stopped = true
}

// Record ingests a single command result.
func (c *Collector) Record(r command.Result) {
	c.totalRequests++
	if !r.Success {
		c.totalErrors++
	}
	m := c.commands[r.Name]
	if m == nil {
		m = newCommandMetrics(r.Name)
		c.commands[r.Name] = m
	}
	m.record(r)
}

// MergeFrom folds another collector into this one, widening the phase window to
// min(start)/max(stop) so duration reflects the whole parallel run.
func (c *Collector) MergeFrom(other *Collector) {
	c.totalRequests += other.totalRequests
	c.totalErrors += other.totalErrors
	for name, om := range other.commands {
		m := c.commands[name]
		if m == nil {
			m = newCommandMetrics(name)
			c.commands[name] = m
		}
		m.mergeFrom(om)
	}
	if other.started && (!c.started || other.start.Before(c.start)) {
		c.start = other.start
		c.started = true
	}
	if other.stopped && (!c.stopped || other.stop.After(c.stop)) {
		c.stop = other.stop
		c.stopped = true
	}
}

// TotalRequests returns the total request count.
func (c *Collector) TotalRequests() int64 { return c.totalRequests }

// TotalErrors returns the total error count.
func (c *Collector) TotalErrors() int64 { return c.totalErrors }

// StartTime returns the window start (zero if never started).
func (c *Collector) StartTime() time.Time { return c.start }

// StopTime returns the window stop (zero if never stopped).
func (c *Collector) StopTime() time.Time { return c.stop }

// DurationMillis returns the measured window in milliseconds.
func (c *Collector) DurationMillis() int64 {
	if !c.started || !c.stopped {
		return 0
	}
	return c.stop.Sub(c.start).Milliseconds()
}

// Commands returns the per-command metrics map.
func (c *Collector) Commands() map[string]*CommandMetrics { return c.commands }

// SafeMerger serializes MergeFrom calls so parent code can merge worker
// collectors from multiple goroutines without a data race.
type SafeMerger struct {
	mu  sync.Mutex
	dst *Collector
}

// NewSafeMerger wraps a destination collector.
func NewSafeMerger(dst *Collector) *SafeMerger { return &SafeMerger{dst: dst} }

// Merge folds src into the destination under a lock.
func (s *SafeMerger) Merge(src *Collector) {
	s.mu.Lock()
	s.dst.MergeFrom(src)
	s.mu.Unlock()
}
