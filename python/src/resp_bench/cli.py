"""Command-line interface for the resp-bench Python engine.

Implements the shared cross-engine CLI contract: ``--server``, ``--driver``,
``--workload``, ``--metrics``, plus ``--info``, ``--commit-id`` (used by CI),
``--version``. (Deliberately no ``--concurrency`` flag: the asyncio engine has
a single execution model.)
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from typing import List, Optional

from .client.factory import BenchmarkClientFactory
from .command.factory import CommandFactory
from .config.loader import ConfigLoader
from .engine.benchmark import BenchmarkEngine
from .version import VERSION


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="resp-bench",
        description="resp-bench Python engine",
    )
    parser.add_argument("--server", default="localhost:6379", help="Server address HOST:PORT")
    parser.add_argument("--driver", help="Driver configuration file (required)")
    parser.add_argument("--workload", help="Workload configuration file (required)")
    parser.add_argument("--metrics", help="Metrics output file (required)")
    parser.add_argument("--commit-id", dest="commit_id", help="Git commit ID for metadata")
    parser.add_argument("--info", action="store_true", help="Show supported drivers and commands")
    parser.add_argument(
        "--version",
        action="version",
        version=f"resp-bench Python Engine v{VERSION}",
    )
    return parser


def _parse_server(server: str) -> tuple[str, int]:
    host, _, port = server.partition(":")
    return host or "localhost", int(port) if port else 6379


def _print_info() -> None:
    print(f"resp-bench Python Engine v{VERSION}")
    print()
    print("Supported Drivers:")
    for driver in BenchmarkClientFactory.supported_drivers():
        print(f"  - {driver}")
    print()
    print("Supported Commands:")
    for command in CommandFactory.supported_commands():
        print(f"  - {command}")
    print()
    print("Concurrency: asyncio task-per-connection (one client per connection)")


def _validate(options: argparse.Namespace) -> None:
    missing = [
        flag
        for flag, value in (
            ("--driver", options.driver),
            ("--workload", options.workload),
            ("--metrics", options.metrics),
        )
        if not value
    ]
    if missing:
        raise ValueError(f"Missing required options: {', '.join(missing)}")
    if not os.path.exists(options.driver):
        raise ValueError(f"Driver config not found: {options.driver}")
    if not os.path.exists(options.workload):
        raise ValueError(f"Workload config not found: {options.workload}")


async def _run_benchmark(options: argparse.Namespace) -> None:
    host, port = _parse_server(options.server)
    driver_config = ConfigLoader.load_driver_config(options.driver)
    workload_config = ConfigLoader.load_workload_config(options.workload)

    engine = BenchmarkEngine(
        host=host,
        port=port,
        driver_config=driver_config,
        workload_config=workload_config,
        metrics_path=options.metrics,
        commit_id=options.commit_id,
    )
    await engine.run()


def main(argv: Optional[List[str]] = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    options = _build_parser().parse_args(argv)

    if options.info:
        _print_info()
        return 0

    try:
        _validate(options)
        asyncio.run(_run_benchmark(options))
        return 0
    except Exception as exc:  # noqa: BLE001 - top-level CLI error boundary
        print(f"Error: {exc}", file=sys.stderr)
        if os.environ.get("DEBUG"):
            import traceback

            traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
