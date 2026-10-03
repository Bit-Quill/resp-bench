"""Contract tests for benchmark workflow matrix and artifact layout."""

from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = REPO_ROOT / ".github" / "workflows" / "benchmark.yml"


def job_section(name, next_name):
    workflow = WORKFLOW_PATH.read_text()
    return workflow.split(f"  {name}:", 1)[1].split(f"  {next_name}:", 1)[0]


def test_current_ruby_versions_are_compatibility_tests():
    test_ruby = job_section("test-ruby", "benchmark-ruby")

    assert "ruby-version:\n          - '3.2'\n          - '3.4'\n          - '4.0'" in test_ruby
    assert "ruby-version: ${{ matrix.ruby-version }}" in test_ruby
    assert "run: make ruby-unit-test" in test_ruby


def test_ruby_benchmark_keeps_one_result_per_driver_workload():
    benchmark = job_section("benchmark-ruby", "test-php")

    assert "matrix.ruby-version" not in benchmark
    assert "ruby-version: '3.2'" in benchmark
    assert (
        "name: benchmark-ruby-${{ steps.names.outputs.driver_name }}-"
        "${{ steps.names.outputs.workload_name }}"
    ) in benchmark
