import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
TRIGGER_TEMPLATE = REPO_ROOT / "infra" / "aws" / "trigger.yaml"
DEPLOY_SCRIPT = REPO_ROOT / "infra" / "aws" / "deploy-trigger.sh"


def test_inline_lambda_is_valid_python():
    template = TRIGGER_TEMPLATE.read_text(encoding="utf-8")
    code_lines = []
    for line in template.split("        ZipFile: |\n", 1)[1].splitlines():
        if line and not line.startswith("          "):
            break
        code_lines.append(line[10:] if line else "")
    code = "\n".join(code_lines)

    compile(code, str(TRIGGER_TEMPLATE), "exec")
    assert '"ParameterKey": "RubyClientVersion"' in code
    assert '"ParameterKey": "MatrixName"' in code


def test_trigger_deploy_script_has_offline_dry_run():
    result = subprocess.run(
        [DEPLOY_SCRIPT, "--dry-run", "--repo-tag", "release-test"],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    output = " ".join(result.stdout.split())

    assert "no AWS calls made" in output
    assert "templates/benchmark.yaml" in output
    assert "cloudformation deploy" in output
    assert "BenchmarkRepoTag=release-test" in output
