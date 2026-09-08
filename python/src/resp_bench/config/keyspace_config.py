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

    def validate(self) -> None:
        """Reject a keyspace that would crash the key generator mid-run.

        ``keys_count`` reaches a modulo in KeyGenerator, so 0 or a missing value
        would raise ZeroDivisionError/TypeError inside a worker instead of
        failing at config load.
        """
        if self.keys_count is None or self.keys_count < 1:
            raise ValueError("keyspace.keys_count must be a positive integer")
        if self.key_size_bytes < 1:
            raise ValueError("keyspace.key_size_bytes must be a positive integer")
        if self.generation_alg not in ("sequential_int", "uniform_rand"):
            raise ValueError(
                f"Unknown keyspace.generation_alg: {self.generation_alg} "
                '(expected "sequential_int" or "uniform_rand")'
            )

    def is_sequential_int(self) -> bool:
        return self.generation_alg == "sequential_int"

    def is_uniform_rand(self) -> bool:
        return self.generation_alg == "uniform_rand"

    def effective_key_prefix(self) -> str:
        return self.key_prefix if self.key_prefix is not None else DEFAULT_KEY_PREFIX

    def seed_value(self) -> int:
        return self.seed if self.seed is not None else 0
