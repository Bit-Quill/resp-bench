"""Key generator producing sequences identical to the other engines.

- ``sequential_int``: keys 0, 1, 2, ... N-1, wrapping around.
- ``uniform_rand``: Java-LCG random keys (see :mod:`.java_random`).

Key formatting matches Java's ``String.format("%0Nd", index)``: the numeric
part is zero-padded to ``max(key_size_bytes - len(prefix), 1)`` digits.

Cross-worker semantics follow the Java reference:
- ``sequential_int`` uses a counter SHARED across all workers in a phase, so
  the workers collectively emit 0, 1, 2, ... (this is what populates the whole
  keyspace during a warmup/populate phase). Pass a shared :class:`Counter`.
- ``uniform_rand`` uses a per-worker RNG seeded ``base_seed + worker_index``.
"""

from __future__ import annotations

from typing import Optional

from ..config.keyspace_config import KeyspaceConfig
from .java_random import JavaRandom


class Counter:
    """A monotonic 0-based counter. Shared across a phase's workers.

    Safe to share across asyncio tasks: ``next_value`` performs its
    read-increment with no ``await`` in between, so it is atomic on the single
    event-loop thread.
    """

    def __init__(self, start: int = 0) -> None:
        self._value = start

    def next_value(self) -> int:
        current = self._value
        self._value += 1
        return current

    def reset(self) -> None:
        self._value = 0


class KeyGenerator:
    def __init__(
        self,
        config: KeyspaceConfig,
        seed_override: Optional[int] = None,
        sequential_counter: Optional[Counter] = None,
    ) -> None:
        self._config = config
        self._key_prefix = config.effective_key_prefix()
        self._key_size_bytes = config.key_size_bytes
        self._keys_count = config.keys_count
        self._seed = seed_override if seed_override is not None else config.seed_value()
        self._sequential_counter = sequential_counter or Counter()
        self._random = JavaRandom(self._seed)

    @classmethod
    def create(cls, config: KeyspaceConfig) -> "KeyGenerator":
        return cls(config)

    @classmethod
    def create_with_seed(
        cls,
        config: KeyspaceConfig,
        seed: int,
        sequential_counter: Optional[Counter] = None,
    ) -> "KeyGenerator":
        """Per-worker generator with a unique seed and an optional shared counter."""
        return cls(config, seed_override=seed, sequential_counter=sequential_counter)

    def next_key(self) -> str:
        if self._config.is_sequential_int():
            key_index = self._sequential_counter.next_value()
        else:
            key_index = self._random.next_int(self._keys_count)

        key_index %= self._keys_count
        return self._format_key(key_index)

    def reset(self) -> None:
        self._sequential_counter.reset()
        self._random.set_seed(self._seed)

    def _format_key(self, key_index: int) -> str:
        padding_width = max(self._key_size_bytes - len(self._key_prefix), 1)
        return f"{self._key_prefix}{key_index:0{padding_width}d}"
