#!/usr/bin/env bash

set -euo pipefail

readonly DEFAULT_REGION="us-east-1"
readonly DEFAULT_STACK_NAME="resp-bench-ruby-trigger"
readonly DEFAULT_BUCKET="valkey-glide-resp-bench"
readonly DEFAULT_TEMPLATE_KEY="templates/benchmark.yaml"
readonly DEFAULT_FUNCTION_NAME="resp-bench-trigger-ruby"
readonly DEFAULT_GITHUB_REPOSITORY="valkey-io/valkey-glide-ruby"
readonly DEFAULT_REPO_URL="https://github.com/Bit-Quill/resp-bench.git"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly RUNNER_TEMPLATE="${SCRIPT_DIR}/benchmark.yaml"
readonly TRIGGER_TEMPLATE="${SCRIPT_DIR}/trigger.yaml"

REGION="${DEFAULT_REGION}"
STACK_NAME="${DEFAULT_STACK_NAME}"
TEMPLATE_BUCKET="${DEFAULT_BUCKET}"
RESULTS_BUCKET="${DEFAULT_BUCKET}"
TEMPLATE_KEY="${DEFAULT_TEMPLATE_KEY}"
FUNCTION_NAME="${DEFAULT_FUNCTION_NAME}"
GITHUB_REPOSITORY="${DEFAULT_GITHUB_REPOSITORY}"
REPO_URL="${DEFAULT_REPO_URL}"
REPO_TAG="main"
DRY_RUN=0

die() {
  printf 'deploy-trigger: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: infra/aws/deploy-trigger.sh [options]

Deploy or update the private Ruby RESPBench Lambda trigger and upload the
benchmark runner template to S3.

Options:
  --region <region>              AWS region (default: us-east-1)
  --stack-name <name>            Trigger stack name
  --template-bucket <bucket>     Existing private S3 artifact bucket
  --results-bucket <bucket>      Existing private S3 results bucket
  --function-name <name>         Lambda function name
  --github-repository <owner/repo>
                                  Repository trusted through GitHub OIDC
  --repo-url <url>               RESPBench git URL used by benchmark instances
  --repo-tag <ref>               RESPBench git ref used by benchmark instances
  --dry-run                      Print commands without calling AWS
  -h, --help                     Show this help
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --region) REGION="$2"; shift 2 ;;
    --stack-name) STACK_NAME="$2"; shift 2 ;;
    --template-bucket) TEMPLATE_BUCKET="$2"; shift 2 ;;
    --results-bucket) RESULTS_BUCKET="$2"; shift 2 ;;
    --function-name) FUNCTION_NAME="$2"; shift 2 ;;
    --github-repository) GITHUB_REPOSITORY="$2"; shift 2 ;;
    --repo-url) REPO_URL="$2"; shift 2 ;;
    --repo-tag) REPO_TAG="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[ -f "${RUNNER_TEMPLATE}" ] || die "runner template not found: ${RUNNER_TEMPLATE}"
[ -f "${TRIGGER_TEMPLATE}" ] || die "trigger template not found: ${TRIGGER_TEMPLATE}"

upload_runner=(
  aws s3 cp
  "${RUNNER_TEMPLATE}"
  "s3://${TEMPLATE_BUCKET}/${TEMPLATE_KEY}"
  --region "${REGION}"
  --content-type application/yaml
  --sse AES256
)

if [ "${DRY_RUN}" -ne 1 ]; then
  aws s3api head-bucket --bucket "${TEMPLATE_BUCKET}" --region "${REGION}" >/dev/null
  if [ "${RESULTS_BUCKET}" != "${TEMPLATE_BUCKET}" ]; then
    aws s3api head-bucket --bucket "${RESULTS_BUCKET}" --region "${REGION}" >/dev/null
  fi
fi

deploy_trigger=(
  aws cloudformation deploy
  --region "${REGION}"
  --stack-name "${STACK_NAME}"
  --template-file "${TRIGGER_TEMPLATE}"
  --capabilities CAPABILITY_IAM
  --no-fail-on-empty-changeset
  --tags Project=resp-bench Client=ruby
  --parameter-overrides
    "GitHubRepository=${GITHUB_REPOSITORY}"
    "TriggerFunctionName=${FUNCTION_NAME}"
    "TemplateBucket=${TEMPLATE_BUCKET}"
    "TemplateKey=${TEMPLATE_KEY}"
    "ResultsBucket=${RESULTS_BUCKET}"
    "BenchmarkRepoUrl=${REPO_URL}"
    "BenchmarkRepoTag=${REPO_TAG}"
)

if [ "${DRY_RUN}" -eq 1 ]; then
  printf 'DRY RUN - no AWS calls made.\n\n'
  printf '  %q' "${upload_runner[@]}"
  printf '\n'
  printf '  %q' "${deploy_trigger[@]}"
  printf '\n'
  exit 0
fi

"${upload_runner[@]}"
"${deploy_trigger[@]}"

function_name="$(
  aws cloudformation describe-stacks \
    --region "${REGION}" \
    --stack-name "${STACK_NAME}" \
    --query "Stacks[0].Outputs[?OutputKey=='TriggerFunctionName'].OutputValue" \
    --output text
)"
github_role_arn="$(
  aws cloudformation describe-stacks \
    --region "${REGION}" \
    --stack-name "${STACK_NAME}" \
    --query "Stacks[0].Outputs[?OutputKey=='GitHubRoleArn'].OutputValue" \
    --output text
)"

printf 'Trigger deployed.\n'
printf 'Function: %s\n' "${function_name}"
printf 'GitHub role ARN: %s\n' "${github_role_arn}"
printf 'Runner template: s3://%s/%s\n' "${TEMPLATE_BUCKET}" "${TEMPLATE_KEY}"
