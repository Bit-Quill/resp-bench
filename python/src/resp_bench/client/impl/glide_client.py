"""valkey-glide driver using the async GLIDE Python client (``import glide``).

The async client is used deliberately (the sync ``glide_sync`` package is not
used). One ``GlideClient`` is created per connection, honoring the
``client == connection`` invariant shared across engines.
"""

from __future__ import annotations

from ...config.driver_config import DriverConfig
from ..benchmark_client import AsyncBenchmarkClient
from ..timed_result import TimedResult


class GlideBenchmarkClient(AsyncBenchmarkClient):
    def __init__(self) -> None:
        self._client = None

    async def connect(self, host: str, port: int, config: DriverConfig) -> None:
        from glide import (
            GlideClient,
            GlideClientConfiguration,
            GlideClusterClient,
            GlideClusterClientConfiguration,
            NodeAddress,
            ServerCredentials,
        )

        addresses = [NodeAddress(host, port)]

        credentials = None
        if config.auth and (config.auth.get("password") or config.auth.get("username")):
            credentials = ServerCredentials(
                password=config.auth.get("password", ""),
                username=config.auth.get("username"),
            )

        use_tls = config.tls_enabled()
        timeout_ms = config.command_timeout_ms

        if config.is_cluster():
            conf = GlideClusterClientConfiguration(
                addresses=addresses,
                use_tls=use_tls,
                credentials=credentials,
                request_timeout=timeout_ms,
            )
            self._client = await GlideClusterClient.create(conf)
        else:
            conf = GlideClientConfiguration(
                addresses=addresses,
                use_tls=use_tls,
                credentials=credentials,
                request_timeout=timeout_ms,
            )
            self._client = await GlideClient.create(conf)

    async def ping(self) -> TimedResult:
        return await self._measure(lambda: self._client.ping())

    async def get(self, key: str) -> TimedResult:
        return await self._measure(lambda: self._client.get(key))

    async def set(self, key: str, value: bytes) -> TimedResult:
        return await self._measure(lambda: self._client.set(key, value))

    async def close(self) -> None:
        if self._client is not None:
            await self._client.close()

    def driver_version(self) -> str:
        try:
            from importlib.metadata import version

            return version("valkey-glide")
        except Exception:  # noqa: BLE001 - version is best-effort metadata
            return "unknown"
