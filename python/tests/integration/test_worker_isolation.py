"""Worker lifecycle: no orphans, no loop starvation, honest status.

Covers the three failure modes a reviewer reproduced on the original engine:
a dying worker leaving its peers running past client close, a synchronously
failing driver monopolising the event loop, and a warmup failure producing no
metrics row at all.
"""

import asyncio
import json

import pytest

from resp_bench.client import factory
from resp_bench.client.impl.recording_client import RecordingClient
from resp_bench.config.command_config import CommandConfig
from resp_bench.config.completion_config import CompletionConfig
from resp_bench.config.driver_config import DriverConfig
from resp_bench.config.keyspace_config import KeyspaceConfig
from resp_bench.config.phase_config import PhaseConfig
from resp_bench.config.workload_config import WorkloadConfig
from resp_bench.engine.benchmark import BenchmarkEngine


@pytest.fixture(autouse=True)
def _restore_factory():
    original = factory.BenchmarkClientFactory._FACTORIES["recording"]
    yield
    factory.BenchmarkClientFactory._FACTORIES["recording"] = original


def _engine(tmp_path, phase, name="metrics.ndjson"):
    out = tmp_path / name
    return out, BenchmarkEngine(
        host="localhost",
        port=6379,
        driver_config=DriverConfig(driver_id="recording", specific_driver_config={}),
        workload_config=WorkloadConfig(
            schema_version="1.0", benchmark_profile={"name": "t"}, phases=[phase]
        ),
        metrics_path=str(out),
    )


def _phase(**kw):
    defaults = dict(
        id="P",
        connections=4,
        completion=CompletionConfig(type="requests", requests=2000),
        keyspace=KeyspaceConfig(keys_count=50, key_prefix="k:", generation_alg="sequential_int"),
        commands=[CommandConfig(command="set", weight=1.0, data_size_bytes=32)],
        warmup_requests=0,
    )
    defaults.update(kw)
    return PhaseConfig(**defaults)


async def test_gather_all_or_cancel_cancels_siblings():
    # asyncio.gather alone leaves siblings running when one task raises, which is
    # what let workers keep issuing against closed clients.
    from resp_bench.engine.benchmark import _gather_all_or_cancel

    completed = []

    async def slow(tag):
        await asyncio.sleep(0.5)
        completed.append(tag)

    async def boom():
        await asyncio.sleep(0)
        raise RuntimeError("injected")

    with pytest.raises(RuntimeError, match="injected"):
        await _gather_all_or_cancel([slow("a"), boom(), slow("b")])

    # Siblings were cancelled and drained before the exception surfaced.
    assert completed == []
    pending = [t for t in asyncio.all_tasks() if t is not asyncio.current_task() and not t.done()]
    assert pending == []


async def test_driver_failure_is_recorded_not_fatal_and_leaves_no_orphans(tmp_path):
    made = []

    class Dying(RecordingClient):
        async def connect(self, host, port, config):
            await super().connect(host, port, config)
            made.append(self)
            self.calls = 0
            self.post_close = 0
            self._operation_delay_micros = 200

        async def set(self, key, value):
            self.calls += 1
            if not self._connected:
                self.post_close += 1
            # made[0] is the metadata probe; made[1] is the first worker.
            if len(made) > 1 and self is made[1] and self.calls % 5 == 0:
                raise RuntimeError("injected driver failure")
            return await super().set(key, value)

    factory.BenchmarkClientFactory._FACTORIES["recording"] = lambda: Dying()
    out, engine = _engine(tmp_path, _phase(completion=CompletionConfig(type="requests", requests=400)))
    await engine.run()

    # No worker task outlived run(), and nothing issued after close.
    pending = [t for t in asyncio.all_tasks() if t is not asyncio.current_task() and not t.done()]
    assert pending == []
    await asyncio.sleep(0.05)
    assert sum(c.post_close for c in made) == 0, "a worker kept issuing after close"

    row = json.loads(out.read_text().splitlines()[0])
    # The failure is accounted as an error rather than killing the phase, and the
    # full budget is still honoured.
    assert row["totals"]["requests"] == 400
    assert row["totals"]["errors"] > 0
    assert row["phase"]["status"] == "COMPLETED"


async def test_synchronously_failing_driver_does_not_starve_the_loop(tmp_path):
    made = []

    class SyncFail(RecordingClient):
        async def connect(self, host, port, config):
            await super().connect(host, port, config)
            made.append(self)
            self.ops = 0
            # Healthy peers take real (awaited) time; the failing one returns
            # instantly without ever suspending.
            self._operation_delay_micros = 200

        async def set(self, key, value):
            self.ops += 1
            if len(made) > 1 and self is made[1]:
                raise RuntimeError("fails before awaiting anything")
            return await super().set(key, value)

    factory.BenchmarkClientFactory._FACTORIES["recording"] = lambda: SyncFail()
    out, engine = _engine(
        tmp_path, _phase(connections=4, completion=CompletionConfig(type="requests", requests=400))
    )
    await engine.run()

    healthy = [c.ops for c in made[2:]]
    # Before the fix the synchronously-failing worker never suspended and the
    # healthy connections completed zero requests.
    assert all(n > 0 for n in healthy), f"healthy connections were starved: {healthy}"
    row = json.loads(out.read_text().splitlines()[0])
    assert row["totals"]["requests"] == 400
    assert row["metrics"]["SET"]["latency"]["count"] > 0, "no successful latencies recorded"


async def test_all_requests_failing_reports_error_not_completed(tmp_path):
    out, engine = _engine(
        tmp_path,
        _phase(completion=CompletionConfig(type="requests", requests=200)),
    )
    engine._driver_config.specific_driver_config = {"error_rate": 1.0}
    await engine.run()

    row = json.loads(out.read_text().splitlines()[0])
    assert row["totals"]["requests"] == 200
    assert row["totals"]["errors"] == 200
    # A phase with no successful request produced no usable latency data.
    assert row["phase"]["status"] == "ERROR"


async def test_zero_latency_driver_still_shares_work_across_connections(tmp_path):
    # A driver that completes without suspending (in-memory, or a cache hit) must
    # not let one connection monopolise a duration-based phase.
    made = []

    class Counting(RecordingClient):
        async def connect(self, host, port, config):
            await super().connect(host, port, config)
            made.append(self)
            self.ops = 0

        async def set(self, key, value):
            self.ops += 1
            return await super().set(key, value)

    factory.BenchmarkClientFactory._FACTORIES["recording"] = lambda: Counting()
    # operation_delay_micros defaults to 0 -> the driver never suspends.
    out, engine = _engine(
        tmp_path,
        _phase(connections=4, completion=CompletionConfig(type="duration", seconds=1)),
    )
    await engine.run()

    per_conn = [c.ops for c in made[1:]]
    assert all(n > 0 for n in per_conn), f"one connection monopolised the phase: {per_conn}"
    row = json.loads(out.read_text().splitlines()[0])
    assert row["phase"]["status"] == "COMPLETED"


async def test_failure_before_workload_still_writes_row_with_timestamps(tmp_path):
    # Connection setup failing must close what was opened and still emit a row --
    # with real timestamps, since nulls violate the schema and the graph scripts
    # would aggregate the row as a 0-RPS point.
    class NoConnect(RecordingClient):
        async def connect(self, host, port, config):
            raise ConnectionError("server unreachable")

    factory.BenchmarkClientFactory._FACTORIES["recording"] = lambda: NoConnect()
    out, engine = _engine(tmp_path, _phase())

    with pytest.raises(ConnectionError):
        await engine.run()

    row = json.loads(out.read_text().splitlines()[0])
    assert row["phase"]["status"] == "ERROR"
    assert row["phase"]["start_timestamp"] is not None
    assert row["phase"]["finish_timestamp"] is not None
    assert engine.had_error is True


async def test_warmup_failure_still_writes_a_row_and_cancels_peers(tmp_path):
    made = []

    class WarmFail(RecordingClient):
        async def connect(self, host, port, config):
            await super().connect(host, port, config)
            made.append(self)
            self.pings = 0

        async def ping(self):
            self.pings += 1
            if len(made) > 1 and self is made[1]:
                from resp_bench.client.timed_result import TimedResult

                return TimedResult(value=None, latency_micros=1, error=RuntimeError("boom"))
            return await super().ping()

    factory.BenchmarkClientFactory._FACTORIES["recording"] = lambda: WarmFail()
    out, engine = _engine(tmp_path, _phase(warmup_requests=200))
    # Give the healthy peers a real suspension point so cancellation is observable
    # (a zero-latency in-memory client would run all 200 pings in one slice).
    engine._driver_config.specific_driver_config = {"operation_delay_micros": 200}

    with pytest.raises(RuntimeError, match="Warmup"):
        await engine.run()

    # The phase must still be represented in the output rather than vanishing.
    row = json.loads(out.read_text().splitlines()[0])
    assert row["phase"]["status"] == "ERROR"
    # Peers were cancelled rather than left pinging closed clients.
    healthy_pings = [c.pings for c in made[2:]]
    assert all(p < 200 for p in healthy_pings), f"peers ran to completion: {healthy_pings}"
