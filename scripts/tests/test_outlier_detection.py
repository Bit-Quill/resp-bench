"""Tests for outlier detection algorithms in generate_interactive_graphs.py."""
import pytest
from generate_interactive_graphs import (
    find_consensus_outliers,
    detect_modified_zscore,
    detect_iqr,
    detect_pct_deviation,
    detect_grubbs,
)


class TestModifiedZScore:
    def test_no_outliers_in_clean_data(self):
        assert detect_modified_zscore([100, 101, 99, 102, 100, 98, 101]) == set()

    def test_flags_extreme_value(self):
        values = [100, 100, 100, 100, 100, 100, 500]
        flagged = detect_modified_zscore(values)
        assert 6 in flagged

    def test_small_sample_returns_empty(self):
        assert detect_modified_zscore([100, 200]) == set()

    def test_identical_values_returns_empty(self):
        assert detect_modified_zscore([100, 100, 100, 100]) == set()


class TestIQR:
    def test_no_outliers_in_tight_data(self):
        assert detect_iqr([100, 101, 99, 102, 100, 98, 101, 103]) == set()

    def test_flags_extreme_value(self):
        values = [100, 100, 100, 100, 100, 100, 100, 500]
        flagged = detect_iqr(values)
        assert 7 in flagged

    def test_small_sample_returns_empty(self):
        assert detect_iqr([100, 200, 300]) == set()


class TestPercentDeviation:
    def test_no_outliers_in_tight_data(self):
        assert detect_pct_deviation([100, 105, 95, 102, 98]) == set()

    def test_flags_value_over_15pct_deviation(self):
        values = [100, 100, 100, 100, 200]
        flagged = detect_pct_deviation(values)
        assert 4 in flagged

    def test_single_value_returns_empty(self):
        assert detect_pct_deviation([100]) == set()


class TestGrubbs:
    def test_no_outliers_in_clean_data(self):
        assert detect_grubbs([100, 101, 99, 102, 100, 98, 101]) == set()

    def test_flags_extreme_outlier(self):
        values = [100, 100, 100, 100, 100, 100, 100, 100, 100, 1000]
        flagged = detect_grubbs(values)
        assert 9 in flagged

    def test_small_sample_returns_empty(self):
        assert detect_grubbs([100, 200]) == set()

    def test_zero_stdev_returns_empty(self):
        assert detect_grubbs([100, 100, 100]) == set()


class TestConsensusOutliers:
    def test_clean_data_no_outliers(self):
        values = [100, 101, 99, 102, 100, 98, 101, 103, 99, 100]
        assert find_consensus_outliers(values) == set()

    def test_single_obvious_outlier(self):
        values = [100, 100, 100, 100, 100, 100, 100, 100, 100, 500]
        outliers = find_consensus_outliers(values)
        assert 9 in outliers

    def test_multiple_outliers(self):
        values = [100, 100, 500, 100, 100, 600, 100, 100, 100, 100]
        outliers = find_consensus_outliers(values)
        assert 2 in outliers
        assert 5 in outliers

    def test_small_sample_no_outliers(self):
        assert find_consensus_outliers([100, 200]) == set()

    def test_all_identical_no_outliers(self):
        assert find_consensus_outliers([100, 100, 100, 100, 100]) == set()

    def test_moderate_deviation_flagged_in_tight_cluster(self):
        """Characterization test: with tightly clustered data (MAD=2.5),
        even a 15% deviation triggers 3/4 methods (Modified Z-Score, IQR, Grubbs).

        This is the expected behavior — the algorithm is intentionally aggressive
        for benchmark data where 10 iterations should be stable. A 15% jump
        in a tight cluster IS suspicious.

        NOTE: If this sensitivity becomes a problem for real benchmark data
        (e.g., warm-JVM variance), consider increasing IQR_MULTIPLIER or
        MODIFIED_Z_THRESHOLD, but be careful not to mask real outliers.
        """
        values = [95, 100, 105, 98, 102, 97, 103, 99, 101, 115]
        outliers = find_consensus_outliers(values)
        # 115 IS flagged: Modified Z=3.91>3.5, IQR upper=110.5<115, Grubbs agrees
        # pct_deviation does NOT flag (14.4% < 15% threshold)
        assert 9 in outliers  # 3/4 methods agree

        # Verify pct_deviation alone would not flag it
        pct_flags = detect_pct_deviation(values)
        assert 9 not in pct_flags

    def test_value_within_spread_not_flagged(self):
        """A value within the natural spread of varied data is NOT an outlier."""
        values = [80, 90, 100, 110, 120, 85, 95, 105, 115, 125]
        outliers = find_consensus_outliers(values)
        # Wide natural spread (MAD is large) — no outliers
        assert len(outliers) == 0
