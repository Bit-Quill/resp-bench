"""Abstract async benchmark client.

Every driver implements this interface. One client instance maps to exactly one
transport connection (the ``client == connection`` invariant shared by all
engines); the engine never shares a single client across issuers. Commands are
coroutines: a worker coroutine awaits one at a time (pipeline depth 1).
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
