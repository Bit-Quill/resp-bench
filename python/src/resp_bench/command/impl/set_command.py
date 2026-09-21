"""SET command with a pre-generated deterministic value."""

from __future__ import annotations

from typing import Optional

from ...client.benchmark_client import AsyncBenchmarkClient
from ...config.command_config import CommandConfig
from ..command import Command, CommandResult

_PATTERN = b"0123456789ABCDEF"


class SetCommand(Command):
    def __init__(self, config: CommandConfig) -> None:
        super().__init__(config)
        self._value = self._generate_value(self._data_size_bytes)

    async def execute(self, client: AsyncBenchmarkClient, key: Optional[str]) -> CommandResult:
        result = await client.set(key, self._value)
        return CommandResult(
            command_name=self.name,
            latency_micros=result.latency_micros,
            success=result.success,
        )

    @staticmethod
    def _generate_value(size: int) -> bytes:
        repeats = (size // len(_PATTERN)) + 1
        return (_PATTERN * repeats)[:size]
