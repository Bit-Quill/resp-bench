"""Factory mapping command names to implementations."""

from __future__ import annotations

from typing import Dict, List, Type

from ..config.command_config import CommandConfig
from .command import Command
from .impl.get_command import GetCommand
from .impl.ping_command import PingCommand
from .impl.set_command import SetCommand


class CommandFactory:
    _COMMAND_CLASSES: Dict[str, Type[Command]] = {
        "ping": PingCommand,
        "get": GetCommand,
        "set": SetCommand,
        # Future: hget, hset, lpush, lpop, sadd, smembers
    }

    @classmethod
    def create(cls, config: CommandConfig) -> Command:
        command_class = cls._COMMAND_CLASSES.get(config.command)
        if command_class is None:
            raise ValueError(
                f"Unknown command: {config.command}. "
                f"Supported: {', '.join(cls._COMMAND_CLASSES)}"
            )
        return command_class(config)

    @classmethod
    def create_all(cls, configs: List[CommandConfig]) -> List[Command]:
        return [cls.create(c) for c in configs]

    @classmethod
    def supported_commands(cls) -> List[str]:
        return list(cls._COMMAND_CLASSES.keys())
