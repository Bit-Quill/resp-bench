"""redis-py driver using the async client (``redis.asyncio``).

Protocol and retry behaviour are pinned explicitly rather than inherited from the
library defaults, because those defaults changed in redis-py 8.0 (RESP3 by
default, 10 retries with backoff) and differ from other clients'. A benchmark
must measure one round-trip per request, so retries are disabled: a silently
retried failure would otherwise be recorded as a success with an inflated
latency instead of as an error.
"""

from __future__ import annotations

import asyncio

from ...config.driver_config import DriverConfig
from ..benchmark_client import AsyncBenchmarkClient
from ..timed_result import TimedResult


class RedisPyClient(AsyncBenchmarkClient):
    def __init__(self) -> None:
        self._client = None

    async def connect(self, host: str, port: int, config: DriverConfig) -> None:
        import redis.asyncio as redis_async

        from redis.backoff import NoBackoff
        from redis.retry import Retry

        # decode_responses=False keeps values as raw bytes (no decode overhead).
        # protocol=3 and retry=0 are set explicitly so the measured behaviour does
        # not depend on which redis-py version resolved.
        kwargs = {
            "host": host,
            "port": port,
            "decode_responses": False,
            "protocol": 3,
            "retry": Retry(NoBackoff(), 0),
            "retry_on_error": [],
            # redis-py serves every command from an internal pool, so concurrent
            # commands (pipeline_depth > 1) each check out their own socket. The
            # pool is capped at the declared depth so a "connection" can never
            # become more sockets than the phase asked for; at depth 1 this pins
            # it to exactly one socket, which is what the other drivers do.
            "max_connections": self._max_in_flight,
        }

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

    def sockets_per_client(self) -> int:
        # The pool is capped at the declared depth, and prime() fills it, so this
        # is the real number of server connections this client holds.
        return self._max_in_flight

    async def prime(self) -> None:
        """Materialise the whole pool before the measured window.

        redis-py creates a pooled connection on first use, so at depth N only one
        socket exists after connect(). Issuing N concurrent PINGs forces all N to
        be created and handshaked now -- otherwise the first N-1 requests of the
        benchmark each pay a TCP connect plus HELLO. This runs regardless of
        `warmup_requests`, which a workload is allowed to set to 0.
        """
        if self._max_in_flight <= 1:
            return
        await asyncio.gather(*(self._client.ping() for _ in range(self._max_in_flight)))

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

    def driver_details(self) -> dict:
        """Record the negotiated protocol and the actual parser class.

        Which response parser redis-py picks depends on whether the optional C
        extension (hiredis) is importable, so it is recorded rather than assumed.
        """
        details = {
            "resp_protocol": 3,
            "retries": 0,
            "response_parser": "unknown",
            # How this driver achieves pipeline_depth > 1, which is not the same
            # mechanism GLIDE uses -- see the note in the engine docstring.
            "pipelining": "connection-pool (up to pipeline_depth sockets per client)",
        }
        try:
            # RedisCluster has no connection_pool; it manages pools per node.
            pool = getattr(self._client, "connection_pool", None)
            if pool is None:
                nodes = self._client.nodes_manager.nodes_cache
                pool = next(iter(nodes.values())).redis_connection.connection_pool
            conn = pool.make_connection()
            details["response_parser"] = type(conn._parser).__name__
        except Exception:  # noqa: BLE001 - best-effort metadata
            pass
        return details
