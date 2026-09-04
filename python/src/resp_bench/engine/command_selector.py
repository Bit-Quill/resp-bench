"""Weighted command selection.

Uses normalized cumulative weights. Command selection intentionally uses
Python's built-in RNG (not the Java LCG): only key generation must be
cross-engine deterministic, command selection need not be.
"""

from __future__ import annotations

import random
from typing import List

from ..command.command import Command


class CommandSelector:
    def __init__(self, commands: List[Command]) -> None:
        self._commands = commands
        self._cumulative_weights = self._build_cumulative_weights(commands)
        self._random = random.Random()

    def select(self) -> Command:
        r = self._random.random()
        for index, threshold in enumerate(self._cumulative_weights):
            if r <= threshold:
                return self._commands[index]
        return self._commands[-1]

    @staticmethod
    def _build_cumulative_weights(commands: List[Command]) -> List[float]:
        total_weight = sum(c.weight for c in commands)
        if total_weight == 0:
            total_weight = 1.0

        cumulative: List[float] = []
        running = 0.0
        for command in commands:
            running += command.weight / total_weight
            cumulative.append(running)
        return cumulative
