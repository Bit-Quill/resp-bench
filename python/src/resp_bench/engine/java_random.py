"""Faithful port of ``java.util.Random`` (48-bit LCG).

This guarantees identical ``uniform_rand`` key sequences across the Java
(reference), Ruby, C#, and Python engines. Java is the canonical source, so
this port reproduces ``nextInt(bound)`` exactly -- including the 32-bit
signed-overflow rejection in the general case, which is what avoids modulo
bias. (Note: the Ruby port omits that rejection because Ruby integers are
arbitrary-precision; this port emulates the int32 wraparound so it matches the
Java reference rather than the Ruby approximation.)

@see https://docs.oracle.com/javase/8/docs/api/java/util/Random.html
"""

from __future__ import annotations

MULTIPLIER = 0x5DEECE66D
ADDEND = 0xB
MASK = (1 << 48) - 1


def _to_int32(value: int) -> int:
    """Interpret the low 32 bits of ``value`` as a signed 32-bit integer."""
    value &= 0xFFFFFFFF
    return value - 0x100000000 if value >= 0x80000000 else value


class JavaRandom:
    def __init__(self, seed: int) -> None:
        self._seed = self._initial_scramble(seed)

    def set_seed(self, seed: int) -> None:
        self._seed = self._initial_scramble(seed)

    def next_int(self, bound: int) -> int:
        """Return a random int in ``[0, bound)`` matching Java's nextInt(int)."""
        if bound <= 0:
            raise ValueError("bound must be positive")

        # Power-of-two fast path (matches Java exactly).
        if (bound & -bound) == bound:
            return (bound * self._next_bits(31)) >> 31

        # General case: rejection sampling to avoid modulo bias. The rejection
        # condition relies on 32-bit signed overflow, which we emulate.
        while True:
            bits = self._next_bits(31)
            val = bits % bound
            if _to_int32(bits - val + (bound - 1)) >= 0:
                return val

    @staticmethod
    def _initial_scramble(seed: int) -> int:
        return (seed ^ MULTIPLIER) & MASK

    def _next_bits(self, bits: int) -> int:
        self._seed = ((self._seed * MULTIPLIER) + ADDEND) & MASK
        return self._seed >> (48 - bits)
