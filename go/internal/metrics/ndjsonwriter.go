package metrics

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// NdjsonWriter appends one JSON line per phase, matching the schema used by the
// Java/Ruby/Python/PHP/Node engines exactly.
type NdjsonWriter struct {
	path string
	mu   sync.Mutex

	commitID               string
	driverID               string
	primaryDriverVersion   string
	secondaryDriverID      string
	secondaryDriverVersion string
	hasMetadata            bool
}

// NewNdjsonWriter creates a writer for the given output path.
func NewNdjsonWriter(path string) *NdjsonWriter {
	return &NdjsonWriter{path: path}
}

// SetMetadata records the run metadata written into every phase line.
func (w *NdjsonWriter) SetMetadata(commitID, driverID, primaryVersion, secondaryID, secondaryVersion string) {
	w.commitID = commitID
	w.driverID = driverID
	w.primaryDriverVersion = primaryVersion
	w.secondaryDriverID = secondaryID
	w.secondaryDriverVersion = secondaryVersion
	w.hasMetadata = true
}

// summary is the latency percentile summary block.
type summary struct {
	Min  int64 `json:"min"`
	P50  int64 `json:"p50"`
	P95  int64 `json:"p95"`
	P99  int64 `json:"p99"`
	P999 int64 `json:"p999"`
	Max  int64 `json:"max"`
}

type hdrBlock struct {
	Format     string `json:"format"`
	Sigfig     int    `json:"sigfig"`
	PayloadB64 string `json:"payload_b64"`
}

type latencyBlock struct {
	Unit    string    `json:"unit"`
	Count   int64     `json:"count"`
	Summary summary   `json:"summary"`
	HDR     *hdrBlock `json:"hdr,omitempty"`
}

type commandBlock struct {
	Requests int64        `json:"requests"`
	Errors   int64        `json:"errors"`
	Latency  latencyBlock `json:"latency"`
}

type phaseBlock struct {
	ID              string  `json:"id"`
	Status          string  `json:"status"`
	StartTimestamp  *string `json:"start_timestamp"`
	FinishTimestamp *string `json:"finish_timestamp"`
	DurationMs      int64   `json:"duration_ms"`
	Connections     int     `json:"connections"`
}

type metadataBlock struct {
	CommitID               string `json:"commit_id,omitempty"`
	Timestamp              string `json:"timestamp"`
	DriverID               string `json:"driver_id,omitempty"`
	PrimaryDriverVersion   string `json:"primary_driver_version,omitempty"`
	SecondaryDriverID      string `json:"secondary_driver_id,omitempty"`
	SecondaryDriverVersion string `json:"secondary_driver_version,omitempty"`
}

// phaseRecord is the full per-phase JSON object. Metrics is always a JSON object
// ({} when empty), never an array, because the graph consumers index into it.
type phaseRecord struct {
	Metadata *metadataBlock          `json:"metadata,omitempty"`
	Phase    phaseBlock              `json:"phase"`
	Totals   map[string]int64        `json:"totals"`
	Metrics  map[string]commandBlock `json:"metrics"`
}

// WritePhaseResults appends a phase record built from the collector.
func (w *NdjsonWriter) WritePhaseResults(phaseID, status string, connections int, c *Collector) error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if err := os.MkdirAll(filepath.Dir(w.path), 0o755); err != nil {
		return fmt.Errorf("create output dir: %w", err)
	}

	rec := w.buildRecord(phaseID, status, connections, c)
	line, err := json.Marshal(rec)
	if err != nil {
		return fmt.Errorf("marshal phase record: %w", err)
	}

	f, err := os.OpenFile(w.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return fmt.Errorf("open metrics file: %w", err)
	}
	defer f.Close()
	if _, err := f.Write(append(line, '\n')); err != nil {
		return fmt.Errorf("write metrics line: %w", err)
	}
	return nil
}

func (w *NdjsonWriter) buildRecord(phaseID, status string, connections int, c *Collector) phaseRecord {
	rec := phaseRecord{
		Phase: phaseBlock{
			ID:              phaseID,
			Status:          status,
			StartTimestamp:  iso8601(c.StartTime()),
			FinishTimestamp: iso8601(c.StopTime()),
			DurationMs:      c.DurationMillis(),
			Connections:     connections,
		},
		Totals: map[string]int64{
			"requests": c.TotalRequests(),
			"errors":   c.TotalErrors(),
		},
		Metrics: map[string]commandBlock{}, // marshals as {} when empty
	}

	if w.hasMetadata {
		rec.Metadata = &metadataBlock{
			CommitID:               w.commitID,
			Timestamp:              time.Now().UTC().Format("2006-01-02T15:04:05Z"),
			DriverID:               w.driverID,
			PrimaryDriverVersion:   w.primaryDriverVersion,
			SecondaryDriverID:      w.secondaryDriverID,
			SecondaryDriverVersion: w.secondaryDriverVersion,
		}
	}

	for name, cm := range c.Commands() {
		block := commandBlock{
			Requests: cm.Requests,
			Errors:   cm.Errors,
			Latency: latencyBlock{
				Unit:  "us",
				Count: cm.Histogram.TotalCount(),
				Summary: summary{
					Min:  cm.Histogram.Min(),
					P50:  cm.Histogram.ValueAtPercentile(50),
					P95:  cm.Histogram.ValueAtPercentile(95),
					P99:  cm.Histogram.ValueAtPercentile(99),
					P999: cm.Histogram.ValueAtPercentile(99.9),
					Max:  cm.Histogram.Max(),
				},
			},
		}
		if b64, err := EncodeCompressedBase64(cm.Histogram); err == nil {
			block.Latency.HDR = &hdrBlock{Format: "hdr", Sigfig: 3, PayloadB64: b64}
		}
		rec.Metrics[name] = block
	}
	return rec
}

// iso8601 formats a UTC timestamp, or nil if the time is zero.
func iso8601(t time.Time) *string {
	if t.IsZero() {
		return nil
	}
	s := t.UTC().Format("2006-01-02T15:04:05Z")
	return &s
}
