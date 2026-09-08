"""GET command."""

from __future__ import annotations

from typing import Optional

from ...client.benchmark_client import AsyncBenchmarkClient
from ..command import Command, CommandResult


class GetCommand(Command):
    async def execute(self, client: AsyncBenchmarkClient, key: Optional[str]) -> CommandResult:
        result = await client.get(key)
        return CommandResult(
            command_name=self.name,
            latency_micros=result.latency_micros,
            success=result.success,
        )
