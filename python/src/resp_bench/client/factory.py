"""Driver registry: maps ``driver_id`` to a client implementation.

Implementations are imported lazily so that ``--info`` and unit tests do not
require heavy optional dependencies (valkey-glide, redis) to be installed when
a given driver is not used.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Callable, Dict

from ..config.driver_config import DriverConfig

if TYPE_CHECKING:  # pragma: no cover
    from .benchmark_client import AsyncBenchmarkClient


def _make_glide() -> "AsyncBenchmarkClient":
    from .impl.glide_client import GlideBenchmarkClient

    return GlideBenchmarkClient()


def _make_redis_py() -> "AsyncBenchmarkClient":
    from .impl.redis_py_client import RedisPyClient

    return RedisPyClient()


def _make_valkey_py() -> "AsyncBenchmarkClient":
    from .impl.valkey_py_client import ValkeyPyClient

    return ValkeyPyClient()


def _make_recording() -> "AsyncBenchmarkClient":
    from .impl.recording_client import RecordingClient

    return RecordingClient()


class BenchmarkClientFactory:
    # Ordered so --info lists them predictably.
    _FACTORIES: Dict[str, Callable[[], "AsyncBenchmarkClient"]] = {
        "valkey-glide-python": _make_glide,
        "redis-py": _make_redis_py,
        "valkey-py": _make_valkey_py,
        "recording": _make_recording,
    }

    @classmethod
    def supported_drivers(cls) -> list[str]:
        return list(cls._FACTORIES.keys())

    @classmethod
    def create(cls, driver_id: str) -> "AsyncBenchmarkClient":
        key = (driver_id or "").lower()
        factory = cls._FACTORIES.get(key)
        if factory is None:
            raise ValueError(
                f"Unknown driver: {driver_id}. "
                f"Supported: {', '.join(cls._FACTORIES)}"
            )
        return factory()

    @classmethod
    async def create_and_connect(
        cls, host: str, port: int, config: DriverConfig
    ) -> "AsyncBenchmarkClient":
        client = cls.create(config.driver_id)
        await client.connect(host, port, config)
        return client
