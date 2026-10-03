"""Tests for Makefile contracts used by the matrix orchestrator."""

import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_ruby_run_preserves_absolute_orchestrator_paths(tmp_path):
    driver = tmp_path / "generated configs" / "driver.json"
    workload = tmp_path / "generated configs" / "workload.json"
    metrics = tmp_path / "results" / "metrics.ndjson"

    result = subprocess.run(
        [
            "make",
            "-n",
            "ruby-run",
            "SERVER=localhost:6379",
            f"DRIVER={driver}",
            f"WORKLOAD={workload}",
            f"METRICS_OUTPUT={metrics}",
        ],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )

    gemfile = REPO_ROOT / "ruby" / "Gemfile"
    assert f'BUNDLE_GEMFILE="{gemfile}"' in result.stdout
    assert f'--driver "{driver}"' in result.stdout
    assert f'--workload "{workload}"' in result.stdout
    assert f'--metrics "{metrics}"' in result.stdout
    assert "..//" not in result.stdout
