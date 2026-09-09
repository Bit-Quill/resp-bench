import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  decodeBase64,
  encodeBase64,
  HIGHEST_TRACKABLE_VALUE,
  LOWEST_TRACKABLE_VALUE,
  newHistogram,
  SIGNIFICANT_FIGURES,
} from '../../src/metrics/hdrHistogram.js';

describe('HDR histogram', () => {
  it('uses the cross-engine range and precision', () => {
    // Java SynchronizedHistogram(600_000_000, 3), C# LongConcurrentHistogram(1,
    // 600_000_000, 3), Ruby HDRHistogram.new(1, 600_000_000, 3).
    assert.equal(LOWEST_TRACKABLE_VALUE, 1);
    assert.equal(HIGHEST_TRACKABLE_VALUE, 600_000_000);
    assert.equal(SIGNIFICANT_FIGURES, 3);
    const histogram = newHistogram();
    assert.equal(histogram.highestTrackableValue, 600_000_000);
    assert.equal(histogram.numberOfSignificantValueDigits, 3);
  });

  it('emits a HIST-prefixed payload, not a double-encoded one', () => {
    // Java's writer base64s the compressed bytes, which always start with the
    // V2 cookie 0x1c849314 -> "HIST". A payload that does not start with HIST
    // means it was base64-encoded twice and Java/Ruby cannot decode it.
    const histogram = newHistogram();
    histogram.recordValue(1234);
    const payload = encodeBase64(histogram);
    assert.ok(payload.startsWith('HIST'), `payload should start with HIST, got ${payload.slice(0, 12)}`);
    assert.doesNotMatch(payload, /^SElTVA/, 'payload is base64 of "HIST" — encoded twice');
  });

  it('round-trips percentiles and total count', () => {
    const histogram = newHistogram();
    for (let value = 1; value <= 1000; value++) histogram.recordValue(value);

    const decoded = decodeBase64(encodeBase64(histogram));
    assert.equal(decoded.totalCount, histogram.totalCount);
    for (const percentile of [0, 50, 95, 99, 99.9, 100]) {
      assert.equal(
        decoded.getValueAtPercentile(percentile),
        histogram.getValueAtPercentile(percentile),
        `p${percentile} differs after round trip`,
      );
    }
  });

  it('encodes an empty histogram without throwing', () => {
    // An all-errors command still needs an hdr block in the NDJSON.
    const payload = encodeBase64(newHistogram());
    assert.ok(payload.startsWith('HIST'));
    assert.equal(decodeBase64(payload).totalCount, 0);
  });

  it('records the top of the range', () => {
    const histogram = newHistogram();
    histogram.recordValue(HIGHEST_TRACKABLE_VALUE);
    assert.equal(histogram.totalCount, 1);
  });
});
