"""Driver (client library) configuration.

Maps to configs/schemas/driver-config.schema.json. Field names and defaults
mirror the Ruby/Java engines so the same JSON files work across all engines.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional


@dataclass
class DriverConfig:
    schema_version: str = "1.0"
    description: Optional[str] = None
    driver_id: Optional[str] = None
    mode: str = "standalone"
    command_timeout_ms: Optional[int] = None
    tls: Optional[dict] = None
    auth: Optional[dict] = None
    specific_driver_config: dict = field(default_factory=dict)

    def secondary_driver_id(self) -> Optional[Any]:
        """Secondary driver id for composite drivers (e.g. spring-data-*)."""
        return self.specific_driver_config.get("secondary_driver_id")

    def is_standalone(self) -> bool:
        return self.mode == "standalone"

    def is_cluster(self) -> bool:
        return self.mode == "cluster"

    def is_sentinel(self) -> bool:
        return self.mode == "sentinel"

    def tls_enabled(self) -> bool:
        return bool(self.tls and self.tls.get("enabled"))
