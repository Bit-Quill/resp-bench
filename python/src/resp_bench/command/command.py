"""Command base class and the metrics-facing result struct."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional

from ..client.benchmark_client import AsyncBenchmarkClient
from ..config.command_config import CommandConfig


@dataclass
class CommandResult:
    command_name: str
    latency_micros: int
    success: bool


class Command(ABC):
    def __init__(self, config: CommandConfig) -> None:
        self.weight = config.weight
        self.name = config.command.upper()
        self._data_size_bytes = config.data_size_bytes

    @abstractmethod
    async def execute(self, client: AsyncBenchmarkClient, key: Optional[str]) -> CommandResult:
        """Execute against ``client`` (using ``key`` where applicable)."""
