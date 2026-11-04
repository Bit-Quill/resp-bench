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


def test_ruby_gemfile_supports_latest_and_exact_versions():
    ruby_script = """
    $requirements = []
    def source(*) = nil
    def gemspec(*) = nil
    def gem(name, *requirements)
      $requirements << requirements if name == "valkey-glide-rb"
    end
    def group(*)
      yield
    end

    ENV.delete("VALKEY_GLIDE_RUBY_VERSION")
    load ARGV.fetch(0)
    ENV["VALKEY_GLIDE_RUBY_VERSION"] = "2.3.4"
    load ARGV.fetch(0)
    p $requirements
    """
    result = subprocess.run(
        ["ruby", "-e", ruby_script, RUBY_GEMFILE],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )

    assert result.stdout.strip() == '[[], ["2.3.4"]]'
