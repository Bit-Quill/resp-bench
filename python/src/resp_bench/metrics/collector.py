"""Latency metrics collection.

Single-event-loop design: no locks are needed because ``record`` runs to
completion without awaiting, so concurrent worker coroutines never interleave
inside it. Latencies are clamped to 600s before recording and errors are
counted but not recorded into the histogram -- matching the other engines.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Dict, Optional

from ..command.command import CommandResult
from .hdr_encoder import HIGHEST_TRACKABLE_VALUE, new_histogram


class CommandMetrics:
    def __init__(self, command_name: str) -> None:
        self.command_name = command_name
        self.requests = 0
        self.errors = 0
        # Created eagerly (like the Java reference) so the NDJSON hdr block and
        # summary are always present, even for a command that only ever errors
        # (an empty histogram reports count 0 and zero percentiles).
        self._histogram = new_histogram()

    def record(self, result: CommandResult) -> None:
        self.requests += 1
        if result.success:
            latency = min(result.latency_micros, HIGHEST_TRACKABLE_VALUE)
            self._histogram.record_value(latency)
        else:
            self.errors += 1

    @property
    def histogram(self):
        return self._histogram

    def count(self) -> int:
        return self._histogram.get_total_count()

    def min(self) -> int:
        return self._histogram.get_min_value()

    def max(self) -> int:
        return self._histogram.get_max_value()

    def percentile(self, pct: float) -> int:
        return self._histogram.get_value_at_percentile(pct)


class MetricsCollector:
    def __init__(self) -> None:
        self.command_metrics: Dict[str, CommandMetrics] = {}
        self.total_requests = 0
        self.total_errors = 0
        self.start_time: Optional[datetime] = None
        self.end_time: Optional[datetime] = None

    def start(self) -> None:
        self.start_time = datetime.now(timezone.utc)

    def stop(self) -> None:
        self.end_time = datetime.now(timezone.utc)

    def record(self, result: CommandResult) -> None:
        self.total_requests += 1
        if not result.success:
            self.total_errors += 1

        metrics = self.command_metrics.get(result.command_name)
        if metrics is None:
            metrics = CommandMetrics(result.command_name)
            self.command_metrics[result.command_name] = metrics
        metrics.record(result)

    def duration_millis(self) -> int:
        if not self.start_time or not self.end_time:
            return 0
        return int((self.end_time - self.start_time).total_seconds() * 1000)
