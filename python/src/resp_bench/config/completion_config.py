"""Phase completion criteria."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional


@dataclass
class CompletionConfig:
    type: str
    seconds: Optional[int] = None
    requests: Optional[int] = None

    def is_duration_based(self) -> bool:
        return self.type == "duration"

    def is_request_based(self) -> bool:
        return self.type == "requests"

    def duration_seconds(self) -> int:
        return self.seconds if self.seconds is not None else 0

    def total_requests(self) -> int:
        return self.requests if self.requests is not None else 0
