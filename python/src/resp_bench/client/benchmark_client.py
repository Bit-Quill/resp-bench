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

Commands are coroutines. At ``pipeline_depth = 1`` a connection has one command
in flight at a time; above that the engine keeps ``pipeline_depth`` of them in
flight on the same client, so an implementation must tolerate concurrent calls
(see :meth:`AsyncBenchmarkClient.set_max_in_flight`).
"""

from __future__ import annotations

import time
from abc import ABC, abstractmethod
from typing import Awaitable, Callable, TypeVar

from ..config.driver_config import DriverConfig
from .timed_result import TimedResult

T = TypeVar("T")


class AsyncBenchmarkClient(ABC):
    # How many commands the engine will keep in flight on this client, i.e. the
    # phase's pipeline_depth. Declared as a class attribute so it is readable
    # even in subclasses that do not chain __init__.
    _max_in_flight: int = 1

    def set_max_in_flight(self, depth: int) -> None:
        """Declare the pipeline depth this client will be driven at.

        Called by the factory before :meth:`connect`. Drivers that multiplex all
        requests over a single socket (GLIDE) can ignore it. Drivers backed by a
        connection pool (redis-py) **must** use it to bound the pool: otherwise
        concurrent commands check out extra sockets and one "connection" quietly
        becomes several, breaking the ``client == connection`` invariant.
        """
        self._max_in_flight = max(1, depth)

    def sockets_per_client(self) -> int:
        """How many server connections this client actually holds.

        1 for a multiplexing driver at any depth. A pooling driver returns the
        number its pool will grow to, so the metrics row can state the real
        socket count instead of leaving it to be inferred from prose.
        """
        return 1

    async def prime(self) -> None:
        """Open everything ``_max_in_flight`` implies, before measurement starts.

        Called by the factory right after :meth:`connect`. A pooling driver
        creates a pooled socket only when a command needs one, so without this
        the second and later sockets would be opened *inside* the measured window
        and their TCP connect and handshake charged to the first requests. This
        must not depend on ``warmup_requests``, which a workload may set to 0.
        """
        return None

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
