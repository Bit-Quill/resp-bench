from hdrh.histogram import HdrHistogram

from resp_bench.metrics.hdr_encoder import (
    HIGHEST_TRACKABLE_VALUE,
    LOWEST_TRACKABLE_VALUE,
    SIGNIFICANT_FIGURES,
    encode_base64,
    new_histogram,
)


def test_histogram_range_matches_other_engines():
    assert (LOWEST_TRACKABLE_VALUE, HIGHEST_TRACKABLE_VALUE, SIGNIFICANT_FIGURES) == (
        1,
        600_000_000,
        3,
    )


def test_encode_is_base64_v2_compressed_and_decodes():
    h = new_histogram()
    for v in (5, 50, 500, 5000, 50000):
        h.record_value(v)

    b64 = encode_base64(h)
    assert isinstance(b64, str)
    # HdrHistogram base64 of the compressed V2 payload begins with "HIST"
    # (the compressed cookie 0x1c849314) -- same family as the Ruby/Java output.
    assert b64.startswith("HIST")

    decoded = HdrHistogram.decode(b64.encode("ascii"))
    assert decoded.get_total_count() == 5
    assert decoded.get_value_at_percentile(50) == h.get_value_at_percentile(50)
    assert decoded.get_max_value() == h.get_max_value()
