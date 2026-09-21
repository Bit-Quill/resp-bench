"""PING command (ignores the generated key)."""

from __future__ import annotations

from typing import Optional

from ...client.benchmark_client import AsyncBenchmarkClient
from ..command import Command, CommandResult


class PingCommand(Command):
    async def execute(self, client: AsyncBenchmarkClient, key: Optional[str]) -> CommandResult:
        result = await client.ping()
        return CommandResult(
            command_name=self.name,
            latency_micros=result.latency_micros,
            success=result.success,
        )
