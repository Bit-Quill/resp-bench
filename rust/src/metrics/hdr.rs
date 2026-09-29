//! HdrHistogram helpers.
//!
//! Histograms use range `(1, 600_000_000, 3)`: 1 microsecond to 600 seconds at 3
//! significant figures, matching every other engine (Java
//! `SynchronizedHistogram(600_000_000, 3)`, Python/Go/C#/Ruby all use a max of
//! 600_000_000µs).
//!
//! `encode_base64` emits the base64-encoded **V2 + DEFLATE** payload — the same
//! compressed format Java's `encodeIntoCompressedByteBuffer` produces and the
//! Python/Ruby engines emit — so payloads are mutually decodable across engines
//! for cross-language analysis. (Byte-identity is not guaranteed because zlib
//! levels may differ, but decodability — what merge/analysis needs — is.)

use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;
use hdrhistogram::serialization::{Serializer, V2DeflateSerializer};
use hdrhistogram::Histogram;

pub const LOWEST_TRACKABLE_VALUE: u64 = 1;
pub const HIGHEST_TRACKABLE_VALUE: u64 = 600_000_000; // 600 seconds in microseconds
pub const SIGNIFICANT_FIGURES: u8 = 3;

/// Create a histogram with the shared cross-engine bounds.
pub fn new_histogram() -> Histogram<u64> {
    Histogram::new_with_bounds(
        LOWEST_TRACKABLE_VALUE,
        HIGHEST_TRACKABLE_VALUE,
        SIGNIFICANT_FIGURES,
    )
    .expect("valid HDR bounds")
}

/// Return the base64 V2+DEFLATE encoding of `histogram`.
pub fn encode_base64(histogram: &Histogram<u64>) -> String {
    let mut buf = Vec::new();
    V2DeflateSerializer::new()
        .serialize(histogram, &mut buf)
        .expect("HDR serialization should not fail for an in-memory buffer");
    STANDARD.encode(&buf)
}

#[cfg(test)]
mod tests {
    use super::*;
    use hdrhistogram::serialization::Deserializer;

    #[test]
    fn payload_round_trips() {
        let mut h = new_histogram();
        for v in [100u64, 300, 300, 12_351] {
            h.record(v).unwrap();
        }
        let b64 = encode_base64(&h);
        assert!(!b64.is_empty());

        // Decode it back the way a peer engine's analysis would.
        let bytes = STANDARD.decode(&b64).unwrap();
        let restored: Histogram<u64> = Deserializer::new()
            .deserialize(&mut bytes.as_slice())
            .unwrap();
        assert_eq!(restored.len(), 4);
        // Bucket-equivalent min/max, matching Java's getMinValue/getMaxValue.
        assert_eq!(
            restored.value_at_percentile(0.0),
            h.value_at_percentile(0.0)
        );
        assert_eq!(
            restored.value_at_percentile(100.0),
            h.value_at_percentile(100.0)
        );
    }
}
