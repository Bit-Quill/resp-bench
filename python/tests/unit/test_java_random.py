"""JavaRandom parity tests.

The core LCG is anchored to a well-known java.util.Random value, which proves
byte-for-byte compatibility with the Java reference without needing a JVM.
"""

import pytest

from resp_bench.engine.java_random import JavaRandom, _to_int32


def test_seed_zero_matches_known_java_value():
    # java.util.Random(0).nextInt() (i.e. next(32) as a signed int) is the
    # well-documented value -1155484576. This anchors the LCG to real Java.
    rng = JavaRandom(0)
    assert _to_int32(rng._next_bits(32)) == -1155484576


def test_deterministic_sequence():
    a = [JavaRandom(12345).next_int(1000) for _ in range(10)]
    b = [JavaRandom(12345).next_int(1000) for _ in range(10)]
    assert a == b


def test_different_seeds_differ():
    a = [JavaRandom(12345).next_int(1000) for _ in range(10)]
    b = [JavaRandom(54321).next_int(1000) for _ in range(10)]
    assert a != b


def test_set_seed_resets():
    rng = JavaRandom(12345)
    first = [rng.next_int(1000) for _ in range(5)]
    rng.set_seed(12345)
    second = [rng.next_int(1000) for _ in range(5)]
    assert first == second


def test_bound_must_be_positive():
    rng = JavaRandom(12345)
    with pytest.raises(ValueError):
        rng.next_int(0)
    with pytest.raises(ValueError):
        rng.next_int(-1)


def test_values_within_bound():
    rng = JavaRandom(12345)
    for _ in range(1000):
        v = rng.next_int(100)
        assert 0 <= v < 100


def test_power_of_two_bounds():
    rng = JavaRandom(12345)
    for bound in (2, 4, 8, 16, 256, 1024):
        for _ in range(200):
            v = rng.next_int(bound)
            assert 0 <= v < bound
