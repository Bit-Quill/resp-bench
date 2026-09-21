"""Async leaky-bucket rate limiter.

Enforces a constant rate with no burst (evenly-spaced operations), matching the
Java reference's interval math: ``interval_ns = 1_000_000_000 // rate``. Unlike
the blocking engines, ``acquire`` yields the event loop via ``asyncio.sleep``
so other connections' coroutines make progress while this one waits.

A single limiter is shared across all of a phase's worker coroutines. Because
the event loop is single-threaded, the check-and-advance of ``next_allowed``
has no ``await`` between read and write, so it is atomic (no CAS needed).
"""

from __future__ import annotations

import asyncio
import time
from typing import Optional


class RateLimiter:
    def __init__(self, rate_per_second: int) -> None:
        self.rate_per_second = rate_per_second
        self._interval_nanos = 1_000_000_000 // rate_per_second
        self._next_allowed_nanos = time.monotonic_ns()

    @staticmethod
    def create(rate_per_second: int) -> Optional["RateLimiter"]:
        """Return a limiter, or ``None`` for unlimited (rate <= 0)."""
        if rate_per_second <= 0:
            return None
        return RateLimiter(rate_per_second)

    async def acquire(self) -> None:
        while True:
            now = time.monotonic_ns()
            if now >= self._next_allowed_nanos:
                self._next_allowed_nanos += self._interval_nanos
                return
            wait_seconds = (self._next_allowed_nanos - now) / 1_000_000_000
            if wait_seconds > 0:
                await asyncio.sleep(wait_seconds)
