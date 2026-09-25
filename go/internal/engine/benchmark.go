package engine

import (
	"fmt"
	"log"
	"sync"
	"sync/atomic"
	"time"

	"github.com/resp-bench/go/internal/client"
	"github.com/resp-bench/go/internal/command"
	"github.com/resp-bench/go/internal/config"
	"github.com/resp-bench/go/internal/metrics"
)

// Phase status strings written to the NDJSON output.
const (
	StatusCompleted = "COMPLETED"
	StatusError     = "ERROR"
)

// Benchmark orchestrates a workload against a driver, writing per-phase NDJSON.
//
// Concurrency model: one goroutine per connection. For a request-based phase the
// budget is a single shared atomic counter claimed one request at a time (not
// pre-divided per worker), so a slow worker never leaves the target unmet. Each
// worker records into its own lock-free collector; collectors are merged after
// the phase, widening the window to min(start)/max(stop).
type Benchmark struct {
	host     string
	port     int
	driver   config.DriverConfig
	workload config.WorkloadConfig
	writer   *metrics.NdjsonWriter
	commitID string
	logger   *log.Logger
}

// New builds a Benchmark engine.
func New(host string, port int, driver config.DriverConfig, workload config.WorkloadConfig,
	metricsPath, commitID string, logger *log.Logger) *Benchmark {
	return &Benchmark{
		host:     host,
		port:     port,
		driver:   driver,
		workload: workload,
		writer:   metrics.NewNdjsonWriter(metricsPath),
		commitID: commitID,
		logger:   logger,
	}
}

// Run executes all phases. It returns a non-zero exit code if any phase failed,
// so callers can propagate it as the process exit status.
func (b *Benchmark) Run() (int, error) {
	if err := b.rejectUnsupportedKnobs(); err != nil {
		return 1, err
	}

	b.logger.Printf("Starting benchmark: %s", b.workload.Name())
	b.logger.Printf("Driver: %s, mode: %s, server: %s:%d", b.driver.DriverID, b.driver.Mode, b.host, b.port)

	b.setupMetadata()

	exitCode := 0
	for _, phase := range b.workload.Phases {
		status, err := b.executePhase(phase)
		if err != nil {
			b.logger.Printf("phase %s error: %v", phase.ID, err)
		}
		if status != StatusCompleted {
			exitCode = 1
		}
	}
	b.logger.Printf("Benchmark completed")
	return exitCode, nil
}

// rejectUnsupportedKnobs fails loudly for config knobs this engine does not
// honor, rather than silently ignoring them and misreporting results.
func (b *Benchmark) rejectUnsupportedKnobs() error {
	for _, p := range b.workload.Phases {
		if p.PipelineDepth > 1 {
			return fmt.Errorf("phase %s: pipeline_depth > 1 is not supported by the Go engine", p.ID)
		}
		if p.CpsLimit > 0 {
			return fmt.Errorf("phase %s: cps_limit is not supported by the Go engine", p.ID)
		}
		if p.CommandTimeout != nil {
			return fmt.Errorf("phase %s: command_timeout_ms is not supported by the Go engine", p.ID)
		}
	}
	return nil
}

// setupMetadata connects a sample client to read the driver version. Failure is
// non-fatal (metadata falls back to "unknown").
func (b *Benchmark) setupMetadata() {
	sample, err := client.CreateAndConnect(b.host, b.port, b.driver)
	if err != nil {
		b.logger.Printf("metadata: could not connect sample client: %v", err)
		b.writer.SetMetadata(b.commitID, b.driver.DriverID, "unknown", b.driver.SecondaryDriverID(), "")
		return
	}
	defer sample.Close()
	b.writer.SetMetadata(b.commitID, b.driver.DriverID, sample.DriverVersion(),
		b.driver.SecondaryDriverID(), sample.SecondaryDriverVersion())
}

// executePhase runs a single phase and writes its NDJSON record.
func (b *Benchmark) executePhase(phase config.PhaseConfig) (string, error) {
	b.logger.Printf("=== phase %s (%s): connections=%d ===", phase.ID, phase.Description, phase.Connections)

	merged := metrics.NewCollector()
	status, err := b.runWorkers(phase, merged)

	if werr := b.writer.WritePhaseResults(phase.ID, status, phase.Connections, merged); werr != nil {
		return status, fmt.Errorf("write phase results: %w", werr)
	}

	dur := float64(merged.DurationMillis()) / 1000.0
	rps := 0.0
	if dur > 0 {
		rps = float64(merged.TotalRequests()) / dur
	}
	b.logger.Printf("=== phase %s: %s | %.1fs | requests=%d errors=%d | %.0f req/s ===",
		phase.ID, status, dur, merged.TotalRequests(), merged.TotalErrors(), rps)
	return status, err
}

// runWorkers spawns one goroutine per connection, sharing a request budget, and
// merges the results. Returns the phase status.
func (b *Benchmark) runWorkers(phase config.PhaseConfig, merged *metrics.Collector) (string, error) {
	workerCount := phase.Connections
	if workerCount < 1 {
		workerCount = 1
	}

	activeWorkers := b.activeWorkerCount(phase, workerCount)
	keyGenSet := NewKeyGenSet(phase.Keyspace)

	// Shared request budget for request-based phases: one atomic counter for the
	// whole phase, claimed one at a time. -1 means unlimited (duration-based).
	var remaining atomic.Int64
	if phase.Completion.IsRequestBased() {
		remaining.Store(int64(phase.Completion.Requests))
	} else {
		remaining.Store(-1)
	}

	var deadline time.Time
	if phase.Completion.IsDurationBased() {
		deadline = time.Now().Add(time.Duration(phase.Completion.Seconds) * time.Second)
	}

	merger := metrics.NewSafeMerger(merged)
	var wg sync.WaitGroup
	var failures atomic.Int64

	for i := 0; i < activeWorkers; i++ {
		wg.Add(1)
		go func(workerIndex int) {
			defer wg.Done()
			if err := b.runWorker(phase, workerIndex, activeWorkers, keyGenSet, &remaining, deadline, merger); err != nil {
				failures.Add(1)
				b.logger.Printf("worker %d failed: %v", workerIndex, err)
			}
		}(i)
	}
	wg.Wait()

	if failures.Load() > 0 {
		return StatusError, fmt.Errorf("%d of %d workers failed", failures.Load(), activeWorkers)
	}
	return StatusCompleted, nil
}

// activeWorkerCount caps the number of working goroutines. When an rps limit is
// lower than the connection count, only rps_limit workers run (each at ~1 rps);
// otherwise flooring/duplicating the per-worker share would overshoot the target.
func (b *Benchmark) activeWorkerCount(phase config.PhaseConfig, workerCount int) int {
	if phase.HasRpsLimit() && phase.RpsLimit < workerCount {
		if phase.RpsLimit < 1 {
			return 1
		}
		return phase.RpsLimit
	}
	return workerCount
}

// runWorker is the per-connection hot loop. It connects, warms up (PING only),
// starts its own clock, then drains the shared budget (or runs until deadline),
// and finally merges its collector into the shared destination.
func (b *Benchmark) runWorker(phase config.PhaseConfig, workerIndex, workerCount int,
	keyGenSet *KeyGenSet, remaining *atomic.Int64, deadline time.Time, merger *metrics.SafeMerger) (err error) {

	collector := metrics.NewCollector()

	c, err := client.CreateAndConnect(b.host, b.port, b.driver)
	if err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	defer func() {
		_ = c.Close()
		// Always merge whatever this worker collected, even on failure, so the
		// phase window and partial counts are preserved.
		merger.Merge(collector)
	}()

	cmds, err := command.NewAll(phase.Commands)
	if err != nil {
		return err
	}
	selector := NewCommandSelector(cmds)
	keyGen := keyGenSet.ForWorker(workerIndex)

	// Per-worker rps share: the phase limit split across active workers with the
	// remainder distributed to the first workers.
	rps := b.workerRps(phase, workerCount, workerIndex)
	limiter := NewRateLimiter(rps)

	// Warmup runs BEFORE the clock starts and uses PING so it does not consume
	// from the workload key sequence.
	b.runWarmup(c, phase.WarmupRequests, workerCount, workerIndex)

	collector.Start()
	defer collector.Stop()

	durationBased := phase.Completion.IsDurationBased()
	for {
		if durationBased {
			if !time.Now().Before(deadline) {
				return nil
			}
		} else {
			// Claim one request from the shared budget.
			if remaining.Add(-1) < 0 {
				return nil
			}
		}
		limiter.Acquire()
		cmd := selector.Select()
		collector.Record(cmd.Execute(c, keyGen))
	}
}

// runWarmup issues this worker's share of PING warmup requests.
func (b *Benchmark) runWarmup(c client.BenchmarkClient, warmupRequests, workerCount, workerIndex int) {
	if warmupRequests <= 0 {
		return
	}
	share := splitShare(warmupRequests, workerCount, workerIndex)
	for i := 0; i < share; i++ {
		c.Ping()
	}
}

// workerRps returns this worker's rps share, or -1 for unlimited. The limit is
// split across active workers with the remainder distributed; idle workers get 0.
func (b *Benchmark) workerRps(phase config.PhaseConfig, workerCount, workerIndex int) int {
	if !phase.HasRpsLimit() {
		return -1
	}
	return splitShare(phase.RpsLimit, workerCount, workerIndex)
}

// splitShare distributes total across workerCount, giving the first
// (total % workerCount) workers one extra. The shares sum exactly to total.
func splitShare(total, workerCount, workerIndex int) int {
	if workerCount <= 0 {
		return 0
	}
	base := total / workerCount
	if workerIndex < total%workerCount {
		base++
	}
	return base
}
