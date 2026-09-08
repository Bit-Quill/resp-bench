"""Abstract async benchmark client.

Every driver implements this interface. This engine creates **one client
instance per connection** and never shares a client across workers, so a phase's
``connections`` value is also its client count.

Note this is *this engine's* convention, not a property of the suite: among the
other engines' drivers, ``lettuce`` and ``redis-rb`` map a client to a single
connection, but ``jedis`` uses a pool (``JedisPooled``), ``redisson`` a
connection pool, the ``spring-data-*`` drivers a shared template, and
``stackexchange-redis`` a multiplexer. Sharing one multiplexing client across workers was proposed and
declined upstream (ikolomi/resp-bench#11) in favour of keeping the
one-client-per-connection baseline, which is why this engine does the same.

Commands are coroutines: a worker awaits one at a time (pipeline depth 1).
"""

from __future__ import annotations

import time
from abc import ABC, abstractmethod
from typing import Awaitable, Callable, TypeVar

from ..config.driver_config import DriverConfig
from .timed_result import TimedResult

T = TypeVar("T")


class AsyncBenchmarkClient(ABC):
    @abstractmethod
    async def connect(self, host: str, port: int, config: DriverConfig) -> None:
        """Establish the connection to the server."""

    @abstractmethod
    async def ping(self) -> TimedResult:
        """Execute PING, returning a timed result with value ``PONG``."""

    @abstractmethod
    async def get(self, key: str) -> TimedResult:
        """Execute GET, returning a timed result (value or ``None``)."""

    @abstractmethod
    async def set(self, key: str, value: bytes) -> TimedResult:
        """Execute SET, returning a timed result with value ``OK``."""

    @abstractmethod
    async def close(self) -> None:
        """Close the connection."""

    @abstractmethod
    def driver_version(self) -> str:
        """Return the underlying driver library version."""

    def secondary_driver_version(self):  # noqa: D401 - optional for composite drivers
        """Secondary driver version (composite drivers only)."""
        return None

    def driver_details(self) -> dict:
        """Environment-dependent settings worth recording in the metrics output.

        Things like the negotiated RESP protocol and the response-parser class
        change what is actually being measured, so drivers report them here and
        the NDJSON writer records them alongside the driver version.
        """
        return {}

    async def _measure(self, operation: Callable[[], Awaitable[T]]) -> TimedResult:
        """Await ``operation`` and record its latency in microseconds.

        Latency is captured even on error, matching the other engines.
        """
        start = time.perf_counter_ns()
        try:
            value = await operation()
            latency = (time.perf_counter_ns() - start) // 1000
            return TimedResult(value=value, latency_micros=latency)
        except Exception as error:  # noqa: BLE001 - benchmark records all failures
            latency = (time.perf_counter_ns() - start) // 1000
            return TimedResult(value=None, latency_micros=latency, error=error)
