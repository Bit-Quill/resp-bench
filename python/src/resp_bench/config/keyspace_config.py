"""Key-generation configuration for a benchmark phase."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

DEFAULT_KEY_SIZE_BYTES = 16
DEFAULT_KEY_PREFIX = "bench:"


@dataclass
class KeyspaceConfig:
    keys_count: int
    key_size_bytes: int = DEFAULT_KEY_SIZE_BYTES
    key_prefix: str = DEFAULT_KEY_PREFIX
    generation_alg: str = "sequential_int"
    seed: Optional[int] = None

    def __post_init__(self) -> None:
        # Mirror Ruby: nil/None falls back to the default rather than staying None.
        if self.key_size_bytes is None:
            self.key_size_bytes = DEFAULT_KEY_SIZE_BYTES
        if self.key_prefix is None:
            self.key_prefix = DEFAULT_KEY_PREFIX
        if self.generation_alg is None:
            self.generation_alg = "sequential_int"

    def is_sequential_int(self) -> bool:
        return self.generation_alg == "sequential_int"

    def is_uniform_rand(self) -> bool:
        return self.generation_alg == "uniform_rand"

    def effective_key_prefix(self) -> str:
        return self.key_prefix if self.key_prefix is not None else DEFAULT_KEY_PREFIX

    def seed_value(self) -> int:
        return self.seed if self.seed is not None else 0
