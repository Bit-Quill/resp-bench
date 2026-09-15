<?php

declare(strict_types=1);

namespace RespBench\Metrics;

/**
 * Minimal pure-PHP HdrHistogram compatible with the HdrHistogram bucketing scheme
 * used by the Java/Ruby engines (lowest=1, highest=600_000_000, sigfig=3).
 *
 * Implements the standard HdrHistogram value→index mapping so that the encoded
 * V2 payload (see HdrEncoder) is byte-compatible with Java's
 * Histogram.encodeIntoCompressedByteBuffer().
 *
 * Reference: https://github.com/HdrHistogram/HdrHistogram
 */
final class HdrHistogram
{
    public readonly int $lowestTrackableValue;
    public readonly int $highestTrackableValue;
    public readonly int $significantFigures;

    private readonly int $subBucketHalfCountMagnitude;
    private readonly int $subBucketHalfCount;
    private readonly int $subBucketCount;
    private readonly int $subBucketMask;
    private readonly int $unitMagnitude;
    private readonly int $bucketCount;
    public readonly int $countsLen;

    /** @var array<int,int> sparse index => count */
    private array $counts = [];

    private int $totalCount = 0;
    private int $minNonZeroValue = PHP_INT_MAX;
    private int $maxValue = 0;

    public function __construct(
        int $lowestTrackableValue = 1,
        int $highestTrackableValue = 600_000_000,
        int $significantFigures = 3,
    ) {
        $this->lowestTrackableValue = $lowestTrackableValue;
        $this->highestTrackableValue = $highestTrackableValue;
        $this->significantFigures = $significantFigures;

        $largestValueWithSingleUnitResolution = 2 * (int) (10 ** $significantFigures);
        $subBucketCountMagnitude = (int) ceil(log($largestValueWithSingleUnitResolution, 2));
        $this->subBucketHalfCountMagnitude = max($subBucketCountMagnitude, 1) - 1;

        $this->unitMagnitude = (int) floor(log($lowestTrackableValue, 2));

        $this->subBucketCount = 2 ** ($this->subBucketHalfCountMagnitude + 1);
        $this->subBucketHalfCount = $this->subBucketCount >> 1;
        $this->subBucketMask = ($this->subBucketCount - 1) << $this->unitMagnitude;

        // Number of buckets needed to cover the highest trackable value.
        $smallestUntrackableValue = $this->subBucketCount << $this->unitMagnitude;
        $bucketsNeeded = 1;
        while ($smallestUntrackableValue < $highestTrackableValue) {
            if ($smallestUntrackableValue > (PHP_INT_MAX >> 1)) {
                $bucketsNeeded++;
                break;
            }
            $smallestUntrackableValue <<= 1;
            $bucketsNeeded++;
        }
        $this->bucketCount = $bucketsNeeded;
        $this->countsLen = ($this->bucketCount + 1) * ($this->subBucketCount >> 1);
    }

    public function record(int $value): void
    {
        if ($value < 0) {
            return;
        }
        $index = $this->countsIndexFor($value);
        $this->counts[$index] = ($this->counts[$index] ?? 0) + 1;
        $this->totalCount++;

        if ($value > $this->maxValue) {
            $this->maxValue = $value;
        }
        if ($value !== 0 && $value < $this->minNonZeroValue) {
            $this->minNonZeroValue = $value;
        }
    }

    public function recordValueWithCount(int $value, int $count): void
    {
        if ($value < 0 || $count <= 0) {
            return;
        }
        $index = $this->countsIndexFor($value);
        $this->counts[$index] = ($this->counts[$index] ?? 0) + $count;
        $this->totalCount += $count;

        if ($value > $this->maxValue) {
            $this->maxValue = $value;
        }
        if ($value !== 0 && $value < $this->minNonZeroValue) {
            $this->minNonZeroValue = $value;
        }
    }

    public function merge(self $other): void
    {
        for ($i = 0; $i < $other->countsLen; $i++) {
            $count = $other->rawCountAt($i);
            if ($count > 0) {
                $value = $other->valueFromIndex($i);
                $this->recordValueWithCount($value, $count);
            }
        }
    }

    public function totalCount(): int
    {
        return $this->totalCount;
    }

    public function min(): int
    {
        if ($this->totalCount === 0) {
            return 0;
        }

        // If only zero-valued samples were recorded, min is 0.
        return $this->minNonZeroValue === PHP_INT_MAX ? 0 : $this->minNonZeroValue;
    }

    public function max(): int
    {
        return $this->maxValue;
    }

    public function valueAtPercentile(float $percentile): int
    {
        if ($this->totalCount === 0) {
            return 0;
        }

        $requestedPercentile = min(max($percentile, 0.0), 100.0);
        $countAtPercentile = (int) ceil(($requestedPercentile / 100.0) * $this->totalCount);
        $countAtPercentile = max($countAtPercentile, 1);

        $total = 0;
        for ($i = 0; $i < $this->countsLen; $i++) {
            $total += $this->rawCountAt($i);
            if ($total >= $countAtPercentile) {
                $valueAtIndex = $this->valueFromIndex($i);

                return $this->highestEquivalentValue($valueAtIndex);
            }
        }

        return $this->maxValue;
    }

    public function rawCountAt(int $index): int
    {
        return $this->counts[$index] ?? 0;
    }

    /**
     * Highest index with a non-zero count, plus one (the "relevant length").
     */
    public function relevantLength(): int
    {
        if ($this->counts === []) {
            return 0;
        }

        return max(array_keys($this->counts)) + 1;
    }

    // --- HdrHistogram index math (mirrors the Java implementation) ---

    private function countsIndexFor(int $value): int
    {
        $bucketIndex = $this->bucketIndexFor($value);
        $subBucketIndex = $this->subBucketIndexFor($value, $bucketIndex);

        return $this->countsIndex($bucketIndex, $subBucketIndex);
    }

    private function bucketIndexFor(int $value): int
    {
        $pow2ceiling = self::bitLength($value | $this->subBucketMask);

        return $pow2ceiling - $this->unitMagnitude - ($this->subBucketHalfCountMagnitude + 1);
    }

    private function subBucketIndexFor(int $value, int $bucketIndex): int
    {
        return $value >> ($bucketIndex + $this->unitMagnitude);
    }

    private function countsIndex(int $bucketIndex, int $subBucketIndex): int
    {
        $bucketBaseIndex = ($bucketIndex + 1) << $this->subBucketHalfCountMagnitude;
        $offsetInBucket = $subBucketIndex - $this->subBucketHalfCount;

        return $bucketBaseIndex + $offsetInBucket;
    }

    public function valueFromIndex(int $index): int
    {
        $bucketIndex = ($index >> $this->subBucketHalfCountMagnitude) - 1;
        $subBucketIndex = ($index & ($this->subBucketHalfCount - 1)) + $this->subBucketHalfCount;

        if ($bucketIndex < 0) {
            $subBucketIndex -= $this->subBucketHalfCount;
            $bucketIndex = 0;
        }

        return $subBucketIndex << ($bucketIndex + $this->unitMagnitude);
    }

    private function highestEquivalentValue(int $value): int
    {
        return $this->nextNonEquivalentValue($value) - 1;
    }

    private function nextNonEquivalentValue(int $value): int
    {
        return $this->lowestEquivalentValue($value) + $this->sizeOfEquivalentValueRange($value);
    }

    private function lowestEquivalentValue(int $value): int
    {
        $bucketIndex = $this->bucketIndexFor($value);
        $subBucketIndex = $this->subBucketIndexFor($value, $bucketIndex);

        return $subBucketIndex << ($bucketIndex + $this->unitMagnitude);
    }

    private function sizeOfEquivalentValueRange(int $value): int
    {
        $bucketIndex = $this->bucketIndexFor($value);

        return 1 << ($this->unitMagnitude + $bucketIndex);
    }

    private static function bitLength(int $value): int
    {
        $length = 0;
        while ($value > 0) {
            $value >>= 1;
            $length++;
        }

        return $length;
    }
}
