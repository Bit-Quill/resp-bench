// Package metrics implements latency collection, HdrHistogram encoding, and
// NDJSON output compatible with the reference engines.
package metrics

import "math"

// HdrHistogram is a minimal pure-Go HdrHistogram implementing the standard
// value→index bucketing (lowest=1, highest=600_000_000, sigfig=3) so the encoded
// V2 payload (see HdrEncoder) is byte-compatible with Java's
// Histogram.encodeIntoCompressedByteBuffer().
//
// Reference: https://github.com/HdrHistogram/HdrHistogram
type HdrHistogram struct {
	LowestTrackableValue  int64
	HighestTrackableValue int64
	SignificantFigures    int

	subBucketHalfCountMagnitude int
	subBucketHalfCount          int64
	subBucketCount              int64
	subBucketMask               int64
	unitMagnitude               int
	bucketCount                 int
	CountsLen                   int

	counts   map[int]int64 // sparse index → count
	total    int64
	minValue int64
	maxValue int64
}

// NewHdrHistogram builds a histogram with the standard resp-bench parameters.
func NewHdrHistogram(lowest, highest int64, sigFigs int) *HdrHistogram {
	h := &HdrHistogram{
		LowestTrackableValue:  lowest,
		HighestTrackableValue: highest,
		SignificantFigures:    sigFigs,
		counts:                map[int]int64{},
		minValue:              math.MaxInt64,
	}

	largestValueWithSingleUnitResolution := int64(2 * int(math.Pow(10, float64(sigFigs))))
	subBucketCountMagnitude := int(math.Ceil(math.Log2(float64(largestValueWithSingleUnitResolution))))
	if subBucketCountMagnitude < 1 {
		subBucketCountMagnitude = 1
	}
	h.subBucketHalfCountMagnitude = subBucketCountMagnitude - 1

	h.unitMagnitude = int(math.Floor(math.Log2(float64(lowest))))

	h.subBucketCount = int64(1) << (h.subBucketHalfCountMagnitude + 1)
	h.subBucketHalfCount = h.subBucketCount >> 1
	h.subBucketMask = (h.subBucketCount - 1) << h.unitMagnitude

	smallestUntrackable := h.subBucketCount << h.unitMagnitude
	bucketsNeeded := 1
	for smallestUntrackable < highest {
		if smallestUntrackable > (math.MaxInt64 >> 1) {
			bucketsNeeded++
			break
		}
		smallestUntrackable <<= 1
		bucketsNeeded++
	}
	h.bucketCount = bucketsNeeded
	h.CountsLen = (h.bucketCount + 1) * int(h.subBucketCount>>1)
	return h
}

// NewDefaultHdrHistogram returns the shared 1µs..600s / 3-sigfig histogram used
// by every engine. Using a different max would make the encoding incompatible.
func NewDefaultHdrHistogram() *HdrHistogram {
	return NewHdrHistogram(1, 600_000_000, 3)
}

// Record adds a single observation.
func (h *HdrHistogram) Record(value int64) { h.RecordValueWithCount(value, 1) }

// RecordValueWithCount adds count observations of value.
func (h *HdrHistogram) RecordValueWithCount(value, count int64) {
	if value < 0 || count <= 0 {
		return
	}
	idx := h.countsIndexFor(value)
	h.counts[idx] += count
	h.total += count
	if value > h.maxValue {
		h.maxValue = value
	}
	if value != 0 && value < h.minValue {
		h.minValue = value
	}
}

// Merge folds another histogram into this one by re-recording at bucket floors.
func (h *HdrHistogram) Merge(other *HdrHistogram) {
	for i := 0; i < other.CountsLen; i++ {
		if c := other.RawCountAt(i); c > 0 {
			h.RecordValueWithCount(other.ValueFromIndex(i), c)
		}
	}
}

// TotalCount returns the number of recorded observations.
func (h *HdrHistogram) TotalCount() int64 { return h.total }

// Min returns Java getMinValue()-equivalent: the lowest populated bucket's value.
func (h *HdrHistogram) Min() int64 {
	if h.total == 0 {
		return 0
	}
	return h.ValueAtPercentile(0)
}

// Max returns Java getMaxValue()-equivalent: the top populated bucket's ceiling.
func (h *HdrHistogram) Max() int64 {
	if h.total == 0 {
		return 0
	}
	return h.ValueAtPercentile(100)
}

// ValueAtPercentile returns the value at the given percentile [0,100].
func (h *HdrHistogram) ValueAtPercentile(p float64) int64 {
	if h.total == 0 {
		return 0
	}
	if p < 0 {
		p = 0
	}
	if p > 100 {
		p = 100
	}
	countAt := int64(math.Ceil((p / 100.0) * float64(h.total)))
	if countAt < 1 {
		countAt = 1
	}
	var running int64
	for i := 0; i < h.CountsLen; i++ {
		running += h.RawCountAt(i)
		if running >= countAt {
			return h.highestEquivalentValue(h.ValueFromIndex(i))
		}
	}
	return h.maxValue
}

// RawCountAt returns the count at a counts-array index.
func (h *HdrHistogram) RawCountAt(index int) int64 { return h.counts[index] }

// RelevantLength returns the highest non-zero index + 1.
func (h *HdrHistogram) RelevantLength() int {
	maxIdx := -1
	for idx := range h.counts {
		if h.counts[idx] != 0 && idx > maxIdx {
			maxIdx = idx
		}
	}
	return maxIdx + 1
}

// --- HdrHistogram index math (mirrors the Java implementation) ---

func (h *HdrHistogram) countsIndexFor(value int64) int {
	bucketIndex := h.bucketIndexFor(value)
	subBucketIndex := h.subBucketIndexFor(value, bucketIndex)
	return h.countsIndex(bucketIndex, subBucketIndex)
}

func (h *HdrHistogram) bucketIndexFor(value int64) int {
	pow2ceiling := bitLength(value | h.subBucketMask)
	return pow2ceiling - h.unitMagnitude - (h.subBucketHalfCountMagnitude + 1)
}

func (h *HdrHistogram) subBucketIndexFor(value int64, bucketIndex int) int64 {
	return value >> uint(bucketIndex+h.unitMagnitude)
}

func (h *HdrHistogram) countsIndex(bucketIndex int, subBucketIndex int64) int {
	bucketBaseIndex := (bucketIndex + 1) << h.subBucketHalfCountMagnitude
	offsetInBucket := int(subBucketIndex - h.subBucketHalfCount)
	return bucketBaseIndex + offsetInBucket
}

// ValueFromIndex reconstructs the bucket floor value for a counts-array index.
func (h *HdrHistogram) ValueFromIndex(index int) int64 {
	bucketIndex := (index >> h.subBucketHalfCountMagnitude) - 1
	subBucketIndex := int64(index&int(h.subBucketHalfCount-1)) + h.subBucketHalfCount
	if bucketIndex < 0 {
		subBucketIndex -= h.subBucketHalfCount
		bucketIndex = 0
	}
	return subBucketIndex << uint(bucketIndex+h.unitMagnitude)
}

func (h *HdrHistogram) highestEquivalentValue(value int64) int64 {
	return h.nextNonEquivalentValue(value) - 1
}

func (h *HdrHistogram) nextNonEquivalentValue(value int64) int64 {
	return h.lowestEquivalentValue(value) + h.sizeOfEquivalentValueRange(value)
}

func (h *HdrHistogram) lowestEquivalentValue(value int64) int64 {
	bucketIndex := h.bucketIndexFor(value)
	subBucketIndex := h.subBucketIndexFor(value, bucketIndex)
	return subBucketIndex << uint(bucketIndex+h.unitMagnitude)
}

func (h *HdrHistogram) sizeOfEquivalentValueRange(value int64) int64 {
	bucketIndex := h.bucketIndexFor(value)
	return int64(1) << uint(h.unitMagnitude+bucketIndex)
}

// bitLength returns the number of bits needed to represent value (>=0).
func bitLength(value int64) int {
	length := 0
	for value > 0 {
		value >>= 1
		length++
	}
	return length
}
