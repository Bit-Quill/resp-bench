"""Phase completion criteria."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional


DURATION = "duration"
REQUESTS = "requests"


@dataclass
class CompletionConfig:
    type: str
    seconds: Optional[int] = None
    requests: Optional[int] = None

    def __post_init__(self) -> None:
        # Compared case-insensitively, matching Java (equalsIgnoreCase) and C#
        # (OrdinalIgnoreCase) so a config that works on those engines works here.
        if isinstance(self.type, str):
            self.type = self.type.strip().lower()

    def validate(self) -> None:
        """Reject configs that would otherwise run zero requests silently.

        Mirrors the Java reference's CompletionConfig.validate(): the type is
        required and must be known, and the relevant bound must be positive.
        """
        if not self.type:
            raise ValueError("completion.type is required (\"duration\" or \"requests\")")
        if self.type == DURATION:
            if not self.seconds or self.seconds <= 0:
                raise ValueError(
                    "completion.seconds must be a positive integer for a duration phase"
                )
        elif self.type == REQUESTS:
            if not self.requests or self.requests <= 0:
                raise ValueError(
                    "completion.requests must be a positive integer for a requests phase"
                )
        else:
            raise ValueError(
                f"Unknown completion type: {self.type} (expected \"duration\" or \"requests\")"
            )

    def is_duration_based(self) -> bool:
        return self.type == DURATION

    def is_request_based(self) -> bool:
        return self.type == REQUESTS

    def duration_seconds(self) -> int:
        return self.seconds if self.seconds is not None else 0

    def total_requests(self) -> int:
        return self.requests if self.requests is not None else 0
