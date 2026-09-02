from resp_bench.command.factory import CommandFactory
from resp_bench.config.command_config import CommandConfig
from resp_bench.engine.command_selector import CommandSelector


def test_respects_weights_approximately():
    commands = CommandFactory.create_all(
        [
            CommandConfig(command="get", weight=0.8),
            CommandConfig(command="set", weight=0.2, data_size_bytes=64),
        ]
    )
    selector = CommandSelector(commands)

    counts = {"GET": 0, "SET": 0}
    n = 20000
    for _ in range(n):
        counts[selector.select().name] += 1

    get_fraction = counts["GET"] / n
    assert 0.75 <= get_fraction <= 0.85


def test_single_command_always_selected():
    commands = CommandFactory.create_all([CommandConfig(command="ping", weight=1.0)])
    selector = CommandSelector(commands)
    assert all(selector.select().name == "PING" for _ in range(100))
