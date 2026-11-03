"""Tests for the Ruby version input used by the AWS benchmark runner."""

import os
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
AWS_RUNNER = REPO_ROOT / "infra" / "aws" / "bench-aws.sh"
RUBY_GEMFILE = REPO_ROOT / "ruby" / "Gemfile"


def test_aws_dry_run_passes_ruby_client_version_to_cloudformation():
    result = subprocess.run(
        [
            AWS_RUNNER,
            "deploy",
            "--dry-run",
            "--matrix",
            "valkey-glide-basic-multiclients",
            "--ruby-client-version",
            "1.2.3-rc4",
        ],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )

    assert "RubyClientVersion=1.2.3-rc4" in result.stdout


def test_aws_runner_defaults_to_latest_ruby_client_version():
    result = subprocess.run(
        [
            AWS_RUNNER,
            "deploy",
            "--dry-run",
        ],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )

    assert "RubyClientVersion=latest" in result.stdout


def test_ruby_gemfile_supports_latest_and_exact_versions():
    ruby_script = """
    def source(*) = nil
    def gemspec(*) = nil
    def gem(name, *requirements)
      puts requirements.first if name == "valkey-glide-rb"
    end
    def group(*)
      yield
    end
    load ARGV.fetch(0)
    """
    latest_env = os.environ.copy()
    latest_env.pop("VALKEY_GLIDE_RUBY_VERSION", None)
    latest_result = subprocess.run(
        ["ruby", "-e", ruby_script, RUBY_GEMFILE],
        cwd=REPO_ROOT,
        env=latest_env,
        check=True,
        capture_output=True,
        text=True,
    )

    exact_result = subprocess.run(
        ["ruby", "-e", ruby_script, RUBY_GEMFILE],
        cwd=REPO_ROOT,
        env=os.environ | {"VALKEY_GLIDE_RUBY_VERSION": "2.3.4"},
        check=True,
        capture_output=True,
        text=True,
    )

    assert latest_result.stdout.strip() == ""
    assert exact_result.stdout.strip() == "2.3.4"
