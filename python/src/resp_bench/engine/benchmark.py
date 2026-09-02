"""Async benchmark engine.

Concurrency model (see the issue analysis): a single asyncio event loop with
**one client per connection** (the ``client == connection`` invariant) and
**one worker coroutine per connection**, all run concurrently via
``asyncio.gather``. Each worker awaits one command at a time -- i.e.
``pipeline_depth`` is effectively 1. This is the faithful async analogue of the
Java/Ruby "one in-flight request per connection" model, so results stay
comparable across engines.

``pipeline_depth > 1`` (multiple in-flight requests per connection) is
intentionally NOT implemented in v1 (deferred; the worker loop is the natural
extension point).
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import List, Optional

from ..client.benchmark_client import AsyncBenchmarkClient
from ..client.factory import BenchmarkClientFactory
from ..command.command import Command, CommandResult
from ..command.factory import CommandFactory
from ..config.driver_config import DriverConfig
from ..config.phase_config import PhaseConfig
from ..config.workload_config import WorkloadConfig
from ..metrics.collector import MetricsCollector
from ..metrics.ndjson_writer import NdjsonWriter
from .command_selector import CommandSelector
from .key_generator import Counter, KeyGenerator
from .rate_limiter import RateLimiter

logger = logging.getLogger("resp_bench")


class BenchmarkEngine:
    def __init__(
        self,
        *,
        host: str,
        port: int,
        driver_config: DriverConfig,
        workload_config: WorkloadConfig,
        metrics_path: str,
        commit_id: Optional[str] = None,
    ) -> None:
        self._host = host
        self._port = port
        self._driver_config = driver_config
        self._workload_config = workload_config
        self._writer = NdjsonWriter(metrics_path)
        self._commit_id = commit_id

    async def run(self) -> None:
        logger.info("Starting benchmark: %s", self._workload_config.name())
        logger.info(
            "Driver: %s, Server mode: %s",
            self._driver_config.driver_id,
            self._driver_config.mode,
        )
        logger.info("Concurrency: asyncio task-per-connection (one client per connection)")
        logger.info("Server: %s:%s", self._host, self._port)

        await self._setup_metadata()

        for phase in self._workload_config.phases:
            await self._execute_phase(phase)

        logger.info("Benchmark completed")

    async def _setup_metadata(self) -> None:
        try:
            sample = await BenchmarkClientFactory.create_and_connect(
                self._host, self._port, self._driver_config
            )
            self._writer.set_metadata(
                commit_id=self._commit_id,
                driver_id=self._driver_config.driver_id,
                primary_driver_version=sample.driver_version(),
                secondary_driver_id=self._driver_config.secondary_driver_id(),
                secondary_driver_version=sample.secondary_driver_version(),
            )
            logger.info(
                "Metadata: commit=%s, driver=%s, version=%s",
                self._commit_id or "N/A",
                self._driver_config.driver_id,
                sample.driver_version(),
            )
            await sample.close()
        except Exception as exc:  # noqa: BLE001 - metadata is best-effort
            logger.warning("Failed to get driver version for metadata: %s", exc)
            self._writer.set_metadata(
                commit_id=self._commit_id,
                driver_id=self._driver_config.driver_id,
                primary_driver_version="unknown",
                secondary_driver_id=self._driver_config.secondary_driver_id(),
                secondary_driver_version=None,
            )

    async def _execute_phase(self, phase: PhaseConfig) -> None:
        logger.info("=== Starting phase: %s (%s) ===", phase.id, phase.description)

        if phase.effective_pipeline_depth() > 1:
            logger.warning(
                "pipeline_depth=%d requested for phase '%s', but the Python engine "
                "does not yet implement pipelining; running at depth 1. Results are "
                "not comparable to pipelined runs of other engines.",
                phase.pipeline_depth,
                phase.id,
            )

        collector = MetricsCollector()
        clients = await self._create_clients(phase)
        commands = CommandFactory.create_all(phase.commands)
        rate_limiter = RateLimiter.create(phase.rps_limit) if phase.has_rps_limit() else None

        try:
            if phase.warmup_requests > 0:
                await self._warmup(clients, phase.warmup_requests)

            collector.start()
            status = await self._run_workload(phase, clients, commands, rate_limiter, collector)
            collector.stop()
        finally:
            await self._close_clients(clients)

        self._writer.write_phase_results(
            phase_id=phase.id,
            status=status,
            connections=phase.connections,
            collector=collector,
        )
        self._log_phase_summary(phase, collector, status)

    async def _create_clients(self, phase: PhaseConfig) -> List[AsyncBenchmarkClient]:
        logger.info("Creating %d connections...", phase.connections)
        cps_limiter = RateLimiter.create(phase.cps_limit) if phase.has_cps_limit() else None

        clients: List[AsyncBenchmarkClient] = []
        for _ in range(phase.connections):
            if cps_limiter is not None:
                await cps_limiter.acquire()
            client = await BenchmarkClientFactory.create_and_connect(
                self._host, self._port, self._driver_config
            )
            clients.append(client)
        logger.info("All %d connections established", len(clients))
        return clients

    async def _warmup(self, clients: List[AsyncBenchmarkClient], warmup_requests: int) -> None:
        logger.info("Warmup: %d PINGs per client...", warmup_requests)

        async def warm(client: AsyncBenchmarkClient) -> None:
            for _ in range(warmup_requests):
                result = await client.ping()
                # Fail fast on an unreachable/misconfigured server rather than
                # running a whole phase that records only errors.
                if not result.success:
                    raise RuntimeError(f"Warmup PING failed: {result.error}")

        await asyncio.gather(*(warm(c) for c in clients))
        logger.info("Warmup completed")

    async def _run_workload(
        self,
        phase: PhaseConfig,
        clients: List[AsyncBenchmarkClient],
        commands: List[Command],
        rate_limiter: Optional[RateLimiter],
        collector: MetricsCollector,
    ) -> str:
        completion = phase.completion
        num_workers = len(clients)
        seed_base = phase.keyspace.seed_value()
        shared_counter = Counter()  # shared across workers for sequential_int

        # Divide a request-based target evenly across workers; duration-based
        # runs use a wall-clock deadline instead.
        target_requests = None if completion.is_duration_based() else completion.total_requests()
        end_time = (
            time.monotonic() + completion.duration_seconds()
            if completion.is_duration_based()
            else None
        )
        per_worker = target_requests // num_workers if target_requests is not None else None
        remainder = target_requests % num_workers if target_requests is not None else 0

        async def worker(idx: int, client: AsyncBenchmarkClient) -> None:
            key_gen = KeyGenerator.create_with_seed(
                phase.keyspace, seed_base + idx, sequential_counter=shared_counter
            )
            selector = CommandSelector(commands)
            my_target = (
                per_worker + (1 if idx < remainder else 0)
                if target_requests is not None
                else None
            )
            count = 0
            while (count < my_target) if my_target is not None else (time.monotonic() < end_time):
                if rate_limiter is not None:
                    await rate_limiter.acquire()
                command = selector.select()
                key = key_gen.next_key()
                try:
                    result = await command.execute(client, key)
                    collector.record(result)
                except Exception:  # noqa: BLE001 - record failures, keep going
                    collector.record(
                        CommandResult(command_name=command.name, latency_micros=0, success=False)
                    )
                count += 1

        logger.info("Starting %d worker coroutines...", num_workers)
        try:
            await asyncio.gather(*(worker(i, c) for i, c in enumerate(clients)))
            logger.info("All operations completed (%d total requests)", collector.total_requests)
            return "COMPLETED"
        except KeyboardInterrupt:  # pragma: no cover
            logger.warning("Workload interrupted")
            return "INTERRUPTED"
        except Exception as exc:  # noqa: BLE001
            logger.error("Error during workload execution: %s", exc)
            return "ERROR"

    async def _close_clients(self, clients: List[AsyncBenchmarkClient]) -> None:
        logger.info("Closing %d connections...", len(clients))
        for client in clients:
            try:
                await client.close()
            except Exception as exc:  # noqa: BLE001
                logger.warning("Error closing client: %s", exc)

    def _log_phase_summary(self, phase, collector, status) -> None:
        duration_s = collector.duration_millis() / 1000.0
        total = collector.total_requests
        errors = collector.total_errors
        rps = round(total / duration_s) if duration_s > 0 else 0
        logger.info("=== Phase %s completed: %s ===", phase.id, status)
        logger.info(
            "  Duration: %.1fs | Requests: %d | Errors: %d | RPS: %d",
            duration_s,
            total,
            errors,
            rps,
        )
        for cmd_name, cmd_metrics in collector.command_metrics.items():
            if cmd_metrics.count() == 0:
                continue
            logger.info(
                "  %s: %d req (%d err) | p50=%dus p95=%dus p99=%dus p99.9=%dus | min=%dus max=%dus",
                cmd_name,
                cmd_metrics.requests,
                cmd_metrics.errors,
                cmd_metrics.percentile(50),
                cmd_metrics.percentile(95),
                cmd_metrics.percentile(99),
                cmd_metrics.percentile(99.9),
                cmd_metrics.min(),
                cmd_metrics.max(),
            )
