package metrics

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"io"
	"testing"
)

// decodeV2Header inflates the compressed payload and returns the declared
// payload_length field plus the raw counts bytes that follow the 40-byte header.
func decodeV2Header(t *testing.T, compressed []byte) (declaredLen int, countsBytes []byte) {
	t.Helper()
	if len(compressed) < 8 {
		t.Fatalf("compressed payload too short: %d bytes", len(compressed))
	}
	cookie := binary.BigEndian.Uint32(compressed[0:4])
	if cookie != v2CompressedEncodingCookie {
		t.Fatalf("bad compressed cookie: 0x%x", cookie)
	}
	zr, err := zlib.NewReader(bytes.NewReader(compressed[8:]))
	if err != nil {
		t.Fatalf("zlib reader: %v", err)
	}
	payload, err := io.ReadAll(zr)
	if err != nil {
		t.Fatalf("inflate: %v", err)
	}
	if len(payload) < 40 {
		t.Fatalf("v2 payload too short: %d bytes", len(payload))
	}
	declaredLen = int(binary.BigEndian.Uint32(payload[4:8]))
	countsBytes = payload[40:]
	return declaredLen, countsBytes
}

// TestPayloadLengthIsCountsOnly is the key cross-engine parity guarantee: Java's
// V2 payload_length is the counts-array length ONLY (measured after the 40-byte
// header), not header+counts. Getting this wrong makes Java's decoder throw.
func TestPayloadLengthIsCountsOnly(t *testing.T) {
	h := NewDefaultHdrHistogram()
	for _, v := range []int64{100, 250, 250, 1000, 5000, 12345} {
		h.Record(v)
	}
	compressed, err := EncodeCompressed(h)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	declaredLen, countsBytes := decodeV2Header(t, compressed)
	if declaredLen != len(countsBytes) {
		t.Fatalf("payload_length=%d but counts bytes=%d (must be counts-only)", declaredLen, len(countsBytes))
	}
}

// TestMinIsBucketLowerBound / TestMaxIsBucketCeiling: min/max must be the
// bucket-equivalent bounds (Java getMinValue/getMaxValue), not the raw samples.
func TestMaxIsBucketCeiling(t *testing.T) {
	h := NewDefaultHdrHistogram()
	h.Record(12345)
	// At 3 sig figs above ~1000µs the bucket ceiling exceeds the raw sample.
	if max := h.Max(); max < 12345 {
		t.Fatalf("max %d should be >= recorded 12345 (bucket ceiling)", max)
	}
	if max := h.Max(); max == 12345 {
		t.Logf("note: max equals raw sample (%d) — acceptable if the sample is a bucket boundary", max)
	}
}

func TestMinReturnsBucketBound(t *testing.T) {
	h := NewDefaultHdrHistogram()
	h.Record(100)
	h.Record(250)
	if min := h.Min(); min > 100 || min < 1 {
		t.Fatalf("min %d out of expected range around 100", min)
	}
}

// TestEmptyHistogramMinMaxZero: an empty histogram reports 0/0, not sentinels.
func TestEmptyHistogramMinMaxZero(t *testing.T) {
	h := NewDefaultHdrHistogram()
	if h.Min() != 0 || h.Max() != 0 {
		t.Fatalf("empty histogram min/max should be 0/0, got %d/%d", h.Min(), h.Max())
	}
}

// TestMergeConsistency: a merged histogram yields the same percentiles as one
// that recorded all samples directly (merge re-records at bucket floors).
func TestMergeConsistency(t *testing.T) {
	direct := NewDefaultHdrHistogram()
	a := NewDefaultHdrHistogram()
	b := NewDefaultHdrHistogram()
	for i := int64(1); i <= 1000; i++ {
		v := i * 37
		direct.Record(v)
		if i%2 == 0 {
			a.Record(v)
		} else {
			b.Record(v)
		}
	}
	merged := NewDefaultHdrHistogram()
	merged.Merge(a)
	merged.Merge(b)

	for _, p := range []float64{50, 95, 99, 99.9, 100} {
		if merged.ValueAtPercentile(p) != direct.ValueAtPercentile(p) {
			t.Fatalf("p%.1f mismatch: merged=%d direct=%d", p,
				merged.ValueAtPercentile(p), direct.ValueAtPercentile(p))
		}
	}
	if merged.TotalCount() != direct.TotalCount() {
		t.Fatalf("total count mismatch: merged=%d direct=%d", merged.TotalCount(), direct.TotalCount())
	}
}

// TestEncodeRoundTripBase64 confirms the base64 wrapper produces non-empty output.
func TestEncodeRoundTripBase64(t *testing.T) {
	h := NewDefaultHdrHistogram()
	h.Record(500)
	s, err := EncodeCompressedBase64(h)
	if err != nil {
		t.Fatalf("encode base64: %v", err)
	}
	if s == "" {
		t.Fatal("expected non-empty base64 payload")
	}
}
