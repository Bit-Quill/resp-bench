"""Result of a timed client operation."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional


@dataclass
class TimedResult:
    value: Any
    latency_micros: int
    error: Optional[BaseException] = None

    @property
    def success(self) -> bool:
        return self.error is None
