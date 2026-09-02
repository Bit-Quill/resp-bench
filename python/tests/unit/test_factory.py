import pytest

from resp_bench.client.factory import BenchmarkClientFactory
from resp_bench.command.factory import CommandFactory


def test_supported_drivers():
    drivers = BenchmarkClientFactory.supported_drivers()
    assert drivers == ["valkey-glide-python", "redis-py", "valkey-py", "recording"]


def test_create_recording_driver():
    # The recording driver needs no optional deps and must instantiate.
    client = BenchmarkClientFactory.create("recording")
    assert client.driver_version() == "1.0.0"


def test_create_unknown_driver_raises():
    with pytest.raises(ValueError, match="Unknown driver"):
        BenchmarkClientFactory.create("nope")


def test_supported_commands():
    assert CommandFactory.supported_commands() == ["ping", "get", "set"]
