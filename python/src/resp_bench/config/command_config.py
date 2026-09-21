"""Per-command configuration within a phase."""

from __future__ import annotations

from dataclasses import dataclass

DEFAULT_DATA_SIZE_BYTES = 256


@dataclass
class CommandConfig:
    command: str
    weight: float = 1.0
    data_size_bytes: int = DEFAULT_DATA_SIZE_BYTES

    def __post_init__(self) -> None:
        # Named explicitly: a config entry missing "command" (a `cmd:` typo, say)
        # otherwise died with "'NoneType' object has no attribute 'lower'", which
        # names neither the field nor the phase.
        if not self.command:
            raise ValueError("commands[].command is required and must be non-empty")
        self.command = self.command.lower()
        # A missing weight defaults to 1.0 (matching the Java reference), rather
        # than crashing on float(None).
        self.weight = 1.0 if self.weight is None else float(self.weight)
        if self.data_size_bytes is None:
            self.data_size_bytes = DEFAULT_DATA_SIZE_BYTES
