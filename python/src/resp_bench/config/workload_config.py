"""Configuration for a benchmark workload (a sequence of phases)."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional

from .phase_config import PhaseConfig


@dataclass
class WorkloadConfig:
    schema_version: str
    benchmark_profile: dict = field(default_factory=dict)
    phases: List[PhaseConfig] = field(default_factory=list)

    def name(self) -> Optional[str]:
        return self.benchmark_profile.get("name")

    def description(self) -> Optional[str]:
        return self.benchmark_profile.get("description")
