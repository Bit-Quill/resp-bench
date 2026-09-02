"""redis-py driver using the async client (``redis.asyncio``)."""

from __future__ import annotations

from ...config.driver_config import DriverConfig
from ..benchmark_client import AsyncBenchmarkClient
from ..timed_result import TimedResult


class RedisPyClient(AsyncBenchmarkClient):
    def __init__(self) -> None:
        self._client = None

    async def connect(self, host: str, port: int, config: DriverConfig) -> None:
        import redis.asyncio as redis_async

        # decode_responses=False keeps values as raw bytes (no decode overhead).
        kwargs = {"host": host, "port": port, "decode_responses": False}

        if config.tls_enabled():
            kwargs["ssl"] = True
            tls = config.tls or {}
            if tls.get("ca_path"):
                kwargs["ssl_ca_certs"] = tls["ca_path"]
            if tls.get("cert_path"):
                kwargs["ssl_certfile"] = tls["cert_path"]
            if tls.get("key_path"):
                kwargs["ssl_keyfile"] = tls["key_path"]
            if tls.get("verify_hostname") is False:
                kwargs["ssl_check_hostname"] = False

        if config.auth:
            if config.auth.get("username"):
                kwargs["username"] = config.auth["username"]
            if config.auth.get("password"):
                kwargs["password"] = config.auth["password"]

        if config.command_timeout_ms:
            kwargs["socket_timeout"] = config.command_timeout_ms / 1000.0

        if config.is_cluster():
            self._client = redis_async.RedisCluster(**kwargs)
        else:
            self._client = redis_async.Redis(**kwargs)

        # Establish the connection eagerly so failures surface at connect time.
        await self._client.ping()

    async def ping(self) -> TimedResult:
        return await self._measure(lambda: self._client.ping())

    async def get(self, key: str) -> TimedResult:
        return await self._measure(lambda: self._client.get(key))

    async def set(self, key: str, value: bytes) -> TimedResult:
        return await self._measure(lambda: self._client.set(key, value))

    async def close(self) -> None:
        if self._client is None:
            return
        aclose = getattr(self._client, "aclose", None)
        if aclose is not None:
            await aclose()
        else:  # pragma: no cover - older redis-py
            await self._client.close()

    def driver_version(self) -> str:
        import redis

        return getattr(redis, "__version__", "unknown")
