"""In-memory recording client for server-free testing.

Records operations and supports simulated latency and error injection via
``specific_driver_config`` (``operation_delay_micros``,
``delay_variation_micros``, ``error_rate``, ``error_message``). This lets the
integration tests exercise the full engine without a live server, mirroring the
Ruby recording driver's role.
"""

from __future__ import annotations

import asyncio
import random
import time
from dataclasses import dataclass
from typing import List, Optional

from ...config.driver_config import DriverConfig
from ..benchmark_client import AsyncBenchmarkClient
from ..timed_result import TimedResult


@dataclass
class RecordedOperation:
    command: str
    key: Optional[str]
    value: Optional[bytes]
    success: bool
    error_message: Optional[str]


class RecordingClient(AsyncBenchmarkClient):
    def __init__(self) -> None:
        self.operations: List[RecordedOperation] = []
        self._stored_data: dict = {}
        self._connected = False
        self._operation_delay_micros = 0
        self._delay_variation_micros = 0
        self._error_rate = 0.0
        self._error_message = "Simulated error"
        self._random = random.Random()

    async def connect(self, host: str, port: int, config: DriverConfig) -> None:
        self._connected = True
        cfg = config.specific_driver_config or {}
        self._operation_delay_micros = int(cfg.get("operation_delay_micros", 0) or 0)
        self._delay_variation_micros = int(cfg.get("delay_variation_micros", 0) or 0)
        self._error_rate = float(cfg.get("error_rate", 0.0) or 0.0)
        self._error_message = str(cfg.get("error_message", "Simulated error"))
        self.operations.append(RecordedOperation("CONNECT", None, None, True, None))

    async def ping(self) -> TimedResult:
        latency, success = await self._simulate()
        error = None if success else self._error_message
        self.operations.append(RecordedOperation("PING", None, None, success, error))
        if success:
            return TimedResult(value="PONG", latency_micros=latency)
        return TimedResult(value=None, latency_micros=latency, error=RuntimeError(error))

    async def get(self, key: str) -> TimedResult:
        latency, success = await self._simulate()
        error = None if success else self._error_message
        self.operations.append(RecordedOperation("GET", key, None, success, error))
        if success:
            return TimedResult(value=self._stored_data.get(key), latency_micros=latency)
        return TimedResult(value=None, latency_micros=latency, error=RuntimeError(error))

    async def set(self, key: str, value: bytes) -> TimedResult:
        latency, success = await self._simulate()
        error = None if success else self._error_message
        if success:
            self._stored_data[key] = value
        self.operations.append(RecordedOperation("SET", key, value, success, error))
        if success:
            return TimedResult(value="OK", latency_micros=latency)
        return TimedResult(value=None, latency_micros=latency, error=RuntimeError(error))

    async def close(self) -> None:
        self._connected = False
        self.operations.append(RecordedOperation("CLOSE", None, None, True, None))

    def driver_version(self) -> str:
        return "1.0.0"

    async def _simulate(self) -> tuple[int, bool]:
        start = time.perf_counter_ns()
        delay_micros = self._calculate_delay_micros()
        if delay_micros > 0:
            await asyncio.sleep(delay_micros / 1_000_000)
        latency = (time.perf_counter_ns() - start) // 1000
        return latency, not self._should_simulate_error()

    def _calculate_delay_micros(self) -> int:
        if self._operation_delay_micros <= 0:
            return 0
        delay = self._operation_delay_micros
        if self._delay_variation_micros > 0:
            variation = self._random.randint(
                -self._delay_variation_micros, self._delay_variation_micros
            )
            delay = max(0, delay + variation)
        return delay

    def _should_simulate_error(self) -> bool:
        if self._error_rate <= 0.0:
            return False
        if self._error_rate >= 1.0:
            return True
        return self._random.random() < self._error_rate
