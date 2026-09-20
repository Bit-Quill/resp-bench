"""pipeline_depth > 1: real concurrency, unchanged key stream, bounded drivers.

The reviewer's ask was for the same pipelining the C# engine has, so these tests
assert the property that matters -- that a connection really does hold
``pipeline_depth`` requests in flight -- rather than that a config knob is
accepted.
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
from resp_bench.engine.key_generator import KeyGenerator


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
        driver_config=DriverConfig(
            driver_id="recording",
            # A real suspension point per request, so concurrent slots overlap
            # instead of each running to completion in one scheduler slice.
            specific_driver_config={"operation_delay_micros": 500},
        ),
        workload_config=WorkloadConfig(
            schema_version="1.0", benchmark_profile={"name": "t"}, phases=[phase]
        ),
        metrics_path=str(out),
    )


def _phase(**kw):
    defaults = dict(
        id="P",
        connections=2,
        completion=CompletionConfig(type="requests", requests=200),
        keyspace=KeyspaceConfig(
            keys_count=100_000,
            key_prefix="k:",
            generation_alg="uniform_rand",
            seed=99,
        ),
        commands=[CommandConfig(command="set", weight=1.0, data_size_bytes=8)],
        warmup_requests=0,
    )
    defaults.update(kw)
    return PhaseConfig(**defaults)


class _Tracking(RecordingClient):
    """Records the peak number of requests concurrently in flight on itself."""

    async def connect(self, host, port, config):
        await super().connect(host, port, config)
        self.in_flight = 0
        self.peak_in_flight = 0
        self.keys = []

    async def set(self, key, value):
        self.keys.append(key)
        self.in_flight += 1
        self.peak_in_flight = max(self.peak_in_flight, self.in_flight)
        try:
            return await super().set(key, value)
        finally:
            self.in_flight -= 1


def _install_tracking():
    made = []

    def make():
        client = _Tracking()
        made.append(client)
        return client

    factory.BenchmarkClientFactory._FACTORIES["recording"] = make
    # made[0] is the metadata probe; the workers' clients follow.
    return made


async def test_depth_one_keeps_a_single_request_in_flight(tmp_path):
    made = _install_tracking()
    out, engine = _engine(tmp_path, _phase(pipeline_depth=1))
    await engine.run()

    workers = made[1:]
    assert [c.peak_in_flight for c in workers] == [1, 1]
    assert json.loads(out.read_text().splitlines()[0])["phase"]["pipeline_depth"] == 1


async def test_depth_four_keeps_four_requests_in_flight_per_connection(tmp_path):
    made = _install_tracking()
    out, engine = _engine(tmp_path, _phase(pipeline_depth=4))
    await engine.run()

    workers = made[1:]
    # Two connections, not eight: depth adds in-flight requests, not clients.
    assert len(workers) == 2
    # The pipeline fills, and never exceeds the declared depth.
    assert [c.peak_in_flight for c in workers] == [4, 4]

    row = json.loads(out.read_text().splitlines()[0])
    assert row["phase"]["connections"] == 2
    assert row["phase"]["pipeline_depth"] == 4
    assert row["totals"]["requests"] == 200
    assert row["totals"]["errors"] == 0
    assert row["phase"]["status"] == "COMPLETED"


async def test_metadata_probe_is_not_counted_as_a_connection(tmp_path):
    made = _install_tracking()
    _, engine = _engine(tmp_path, _phase(connections=3, pipeline_depth=2))
    await engine.run()

    assert len(made) == 1 + 3  # probe + 3 connections


@pytest.mark.parametrize("depth", [1, 2, 8])
async def test_key_stream_is_per_connection_not_per_slot(tmp_path, depth):
    # Each connection's slots share one key generator, so a connection draws from
    # the single stream seeded seed_base + index no matter how deep the pipeline
    # is. With a generator per slot, each connection's D slots would replay the
    # same stream and the keys would come out D times over.
    made = _install_tracking()
    phase = _phase(pipeline_depth=depth)
    _, engine = _engine(tmp_path, phase, name=f"m{depth}.ndjson")
    await engine.run()

    workers = made[1:]
    assert sum(len(c.keys) for c in workers) == 200
    seed_base = phase.keyspace.seed_value()
    for idx, client in enumerate(workers):
        expected = KeyGenerator.create_with_seed(phase.keyspace, seed_base + idx)
        # Compare as multisets: slots draw in order but may complete out of
        # order, so the recorded sequence is not necessarily the draw order.
        assert sorted(client.keys) == sorted(
            expected.next_key() for _ in range(len(client.keys))
        )


async def test_depth_is_passed_to_the_driver_before_connect(tmp_path):
    # Pooling drivers size their pool from this, so it has to arrive before
    # connect() rather than after.
    seen = []

    class Probe(RecordingClient):
        def set_max_in_flight(self, depth):
            super().set_max_in_flight(depth)
            seen.append(("set_max_in_flight", depth))

        async def connect(self, host, port, config):
            seen.append(("connect", self._max_in_flight))
            await super().connect(host, port, config)

    factory.BenchmarkClientFactory._FACTORIES["recording"] = lambda: Probe()
    _, engine = _engine(
        tmp_path,
        _phase(connections=1, pipeline_depth=6,
               completion=CompletionConfig(type="requests", requests=10)),
    )
    await engine.run()

    # The worker client (the last pair) was told depth 6 before connecting.
    assert seen[-2:] == [("set_max_in_flight", 6), ("connect", 6)]


async def test_non_positive_depth_falls_back_to_one(tmp_path):
    made = _install_tracking()
    out, engine = _engine(
        tmp_path,
        _phase(pipeline_depth=0, completion=CompletionConfig(type="requests", requests=20)),
    )
    await engine.run()

    assert all(c.peak_in_flight == 1 for c in made[1:])
    assert json.loads(out.read_text().splitlines()[0])["phase"]["pipeline_depth"] == 1


@pytest.mark.parametrize("warmup_requests, depth", [(5, 1), (5, 4), (0, 4)])
async def test_pool_is_primed_before_measuring_regardless_of_warmup(
    tmp_path, warmup_requests, depth
):
    # A pooling driver opens a socket only when a command needs one, so the extra
    # sockets must be created before the measured window -- including when
    # warmup_requests is 0, which the schema allows. That is prime()'s job, not
    # warmup's.
    made = []

    class Pooling(RecordingClient):
        async def connect(self, host, port, config):
            await super().connect(host, port, config)
            made.append(self)
            self.primed_to = 0

        async def prime(self):
            self.primed_to = self._max_in_flight

    factory.BenchmarkClientFactory._FACTORIES["recording"] = lambda: Pooling()
    _, engine = _engine(
        tmp_path,
        _phase(connections=2, pipeline_depth=depth, warmup_requests=warmup_requests,
               completion=CompletionConfig(type="requests", requests=20)),
    )
    await engine.run()

    assert [c.primed_to for c in made[1:]] == [depth, depth]


async def test_warmup_count_is_per_client_not_multiplied_by_depth(tmp_path):
    # The shared schema defines warmup_requests as PINGs *per client*, and
    # Java/Ruby/C# issue exactly that many. Scaling it by depth would make a
    # pipeline_depth sweep also sweep warmup work and server-side state.
    made = []

    class Counting(RecordingClient):
        async def connect(self, host, port, config):
            await super().connect(host, port, config)
            made.append(self)
            self.pings = 0

        async def ping(self):
            self.pings += 1
            return await super().ping()

    factory.BenchmarkClientFactory._FACTORIES["recording"] = lambda: Counting()
    _, engine = _engine(
        tmp_path,
        _phase(connections=2, pipeline_depth=8, warmup_requests=5,
               completion=CompletionConfig(type="requests", requests=20)),
    )
    await engine.run()

    assert [c.pings for c in made[1:]] == [5, 5]


async def test_socket_count_is_reported_for_pooling_drivers(tmp_path):
    # `connections` alone is the wrong socket signal for a pooling driver at
    # depth > 1, and the matrix runner sweeps `connections` as the graph x-axis --
    # so the real count has to be machine-readable, not just described in prose.
    class Pooling(RecordingClient):
        def sockets_per_client(self):
            return self._max_in_flight

    factory.BenchmarkClientFactory._FACTORIES["recording"] = lambda: Pooling()
    out, engine = _engine(
        tmp_path,
        _phase(connections=2, pipeline_depth=4,
               completion=CompletionConfig(type="requests", requests=20)),
    )
    await engine.run()

    phase = json.loads(out.read_text().splitlines()[0])["phase"]
    assert phase["connections"] == 2
    assert phase["pipeline_depth"] == 4
    assert phase["sockets_per_client"] == 4
    assert phase["total_sockets"] == 8


async def test_multiplexing_driver_reports_one_socket_per_client(tmp_path):
    made = _install_tracking()
    out, engine = _engine(
        tmp_path,
        _phase(connections=3, pipeline_depth=4,
               completion=CompletionConfig(type="requests", requests=20)),
    )
    await engine.run()

    phase = json.loads(out.read_text().splitlines()[0])["phase"]
    assert phase["sockets_per_client"] == 1
    assert phase["total_sockets"] == 3
    assert len(made) == 1 + 3


async def test_a_failing_slot_cancels_its_peers_and_leaves_no_orphans(tmp_path):
    # The cancel-and-drain guarantee has to hold per slot, not just per
    # connection, or a depth>1 phase can leak workers past client close.
    made = []

    class Dying(RecordingClient):
        async def connect(self, host, port, config):
            await super().connect(host, port, config)
            made.append(self)
            self.post_close = 0

        async def set(self, key, value):
            if not self._connected:
                self.post_close += 1
            return await super().set(key, value)

        async def ping(self):
            from resp_bench.client.timed_result import TimedResult

            if len(made) > 1 and self is made[1]:
                return TimedResult(value=None, latency_micros=1, error=RuntimeError("boom"))
            return await super().ping()

    factory.BenchmarkClientFactory._FACTORIES["recording"] = lambda: Dying()
    out, engine = _engine(tmp_path, _phase(connections=4, pipeline_depth=4, warmup_requests=200))

    with pytest.raises(RuntimeError, match="Warmup"):
        await engine.run()

    pending = [t for t in asyncio.all_tasks() if t is not asyncio.current_task() and not t.done()]
    assert pending == []
    await asyncio.sleep(0.05)
    assert sum(c.post_close for c in made) == 0
    assert json.loads(out.read_text().splitlines()[0])["phase"]["status"] == "ERROR"
