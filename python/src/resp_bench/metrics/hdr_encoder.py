"""HdrHistogram helpers.

Uses the ``hdrhistogram`` PyPI package (import ``hdrh``), the official Python
port of HdrHistogram. Its ``encode()`` emits the base64-encoded V2 *compressed*
payload -- the same format Java's ``encodeIntoCompressedByteBuffer`` produces
and the Ruby engine emits -- so payloads are mutually decodable across engines
for cross-language analysis. (Byte-identity is not guaranteed because zlib
compression levels may differ, but decodability -- what merge/analysis needs --
is.)

Histograms use range ``(1, 600_000_000, 3)``: 1 microsecond to 600 seconds at 3
significant figures, matching every other engine (Java
``SynchronizedHistogram(600_000_000, 3)``, C# ``LongConcurrentHistogram(1,
600_000_000, 3)``, Ruby ``HDRHistogram.new(1, 600_000_000, 3)``).
"""

from __future__ import annotations

from hdrh.histogram import HdrHistogram

LOWEST_TRACKABLE_VALUE = 1
HIGHEST_TRACKABLE_VALUE = 600_000_000  # 600 seconds in microseconds
SIGNIFICANT_FIGURES = 3


def new_histogram() -> HdrHistogram:
    return HdrHistogram(
        LOWEST_TRACKABLE_VALUE, HIGHEST_TRACKABLE_VALUE, SIGNIFICANT_FIGURES
    )


def encode_base64(histogram: HdrHistogram) -> str:
    """Return the base64 V2-compressed encoding as an ASCII string."""
    encoded = histogram.encode()
    if isinstance(encoded, bytes):
        return encoded.decode("ascii")
    return encoded
