/**
 * HdrHistogram helpers.
 *
 * Uses `hdr-histogram-js`, the TypeScript port of HdrHistogram. Its
 * `encodeIntoCompressedBase64()` emits the base64-encoded V2 *compressed*
 * payload -- the same format Java's `encodeIntoCompressedByteBuffer` +
 * `Base64.getEncoder()` produces (NdjsonMetricsWriter.java:164-180) and the same
 * the Ruby/Python engines emit -- so payloads are mutually decodable across
 * engines for cross-language analysis. (Byte-identity is not guaranteed since
 * zlib compression levels may differ, but decodability -- what merge/analysis
 * needs -- is.)
 *
 * The returned string is ALREADY base64 (it starts `HIST`). Never base64 it
 * again: double-encoding produces a payload Java and Ruby cannot decode.
 *
 * Histograms use range (1, 600_000_000, 3): 1 microsecond to 600 seconds at 3
 * significant figures, matching every other engine (Java
 * `SynchronizedHistogram(600_000_000, 3)`, C# `LongConcurrentHistogram(1,
 * 600_000_000, 3)`, Ruby `HDRHistogram.new(1, 600_000_000, 3)`).
 */

import * as hdr from 'hdr-histogram-js';

export const LOWEST_TRACKABLE_VALUE = 1;
export const HIGHEST_TRACKABLE_VALUE = 600_000_000; // 600 seconds in microseconds
export const SIGNIFICANT_FIGURES = 3;

export type Histogram = hdr.Histogram;

export function newHistogram(): Histogram {
  return hdr.build({
    bitBucketSize: 64,
    autoResize: false,
    lowestDiscernibleValue: LOWEST_TRACKABLE_VALUE,
    highestTrackableValue: HIGHEST_TRACKABLE_VALUE,
    numberOfSignificantValueDigits: SIGNIFICANT_FIGURES,
  });
}

/** Return the base64 V2-compressed encoding, ready for `payload_b64`. */
export function encodeBase64(histogram: Histogram): string {
  return hdr.encodeIntoCompressedBase64(histogram);
}

/** Inverse of `encodeBase64`, used by the parity tests. */
export function decodeBase64(payload: string): Histogram {
  return hdr.decodeFromCompressedBase64(payload);
}
