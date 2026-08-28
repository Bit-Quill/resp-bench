#!/usr/bin/env bash
#
# bench-aws.sh — thin fire-and-forget driver for the resp-bench AWS runner.
#
# Generates a job id, deploys the CloudFormation stack (which provisions an
# EC2 box, runs the sweep, uploads to S3, and self-terminates), prints the S3
# prefix where results will appear, and exits. No polling loop.
#
# Region is PINNED to us-east-1 for every AWS call — never inherited from the
# ambient shell (which may export AWS_REGION=us-west-2). The S3 bucket lives in
# us-east-1.
#
# Subcommands:
#   deploy [flags]          Launch a run (default).
#   check  <job_id> [flags] List the run's S3 prefix + presign the report.
#   delete <stack> [flags]  Delete a leftover stack shell (SG + role).
#
# deploy flags:
#   --matrix <name>         Matrix config name under configs/matrices/
#                           (default: valkey-glide-basic-multiclients — the
#                           smallest shipped matrix, used as a smoke run).
#                           REQUIRE this explicitly for large sweeps.
#   --instance-type <t>     EC2 instance type (default: m5.large).
#   --repo-tag <ref>        Git ref to check out (default: main).
#   --repo-url <url>        Fork URL (default: Bit-Quill/resp-bench).
#   --runtime-cap <min>     Hard runtime cap in minutes (default: 180).
#   --bucket <name>         Destination bucket (default: valkey-glide-resp-bench).
#   --job-id-prefix <p>     Prefix the job id (e.g. nightly, pr-123).
#   --dry-run               Print the resolved deploy command + S3 prefix; do
#                           not call AWS.

set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# Constants (region pinned)
# ─────────────────────────────────────────────────────────────────────────────
readonly REGION="us-east-1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TEMPLATE="${SCRIPT_DIR}/benchmark.yaml"

# ─────────────────────────────────────────────────────────────────────────────
# Defaults
# ─────────────────────────────────────────────────────────────────────────────
MATRIX="valkey-glide-basic-multiclients"
INSTANCE_TYPE="m5.large"
REPO_TAG="main"
REPO_URL="https://github.com/Bit-Quill/resp-bench.git"
RUNTIME_CAP="180"
BUCKET="valkey-glide-resp-bench"
JOB_ID_PREFIX=""
DRY_RUN=0

die() { printf 'bench-aws: %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# ─────────────────────────────────────────────────────────────────────────────
# job_id generation: [prefix-]bench-YYYYMMDD-HHMMSS-<6 random>
# ─────────────────────────────────────────────────────────────────────────────
generate_job_id() {
  local ts rand
  ts="$(date -u +%Y%m%d-%H%M%S)"
  rand="$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom 2>/dev/null | head -c 6 || true)"
  [ -z "${rand}" ] && rand="$(printf '%06x' $((RANDOM * RANDOM % 16777216)))"
  if [ -n "${JOB_ID_PREFIX}" ]; then
    printf '%s-bench-%s-%s' "${JOB_ID_PREFIX}" "${ts}" "${rand}"
  else
    printf 'bench-%s-%s' "${ts}" "${rand}"
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
# deploy
# ─────────────────────────────────────────────────────────────────────────────
cmd_deploy() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --matrix)        MATRIX="$2"; shift 2 ;;
      --instance-type) INSTANCE_TYPE="$2"; shift 2 ;;
      --repo-tag)      REPO_TAG="$2"; shift 2 ;;
      --repo-url)      REPO_URL="$2"; shift 2 ;;
      --runtime-cap)   RUNTIME_CAP="$2"; shift 2 ;;
      --bucket)        BUCKET="$2"; shift 2 ;;
      --job-id-prefix) JOB_ID_PREFIX="$2"; shift 2 ;;
      --dry-run)       DRY_RUN=1; shift ;;
      -h|--help)       usage; exit 0 ;;
      *) die "unknown deploy flag: $1" ;;
    esac
  done

  [ -f "${TEMPLATE}" ] || die "template not found: ${TEMPLATE}"

  local job_id stack_name s3_prefix
  job_id="$(generate_job_id)"
  stack_name="resp-bench-${job_id}"
  s3_prefix="s3://${BUCKET}/runs/${job_id}/"

  # Build the deploy argv (kept as an array so the dry-run prints exactly what
  # would run).
  local -a deploy_cmd=(
    aws cloudformation deploy
    --region "${REGION}"
    --stack-name "${stack_name}"
    --template-file "${TEMPLATE}"
    --capabilities CAPABILITY_IAM
    --tags Project=resp-bench "RunId=${job_id}"
    --parameter-overrides
      "JobId=${job_id}"
      "MatrixName=${MATRIX}"
      "InstanceType=${INSTANCE_TYPE}"
      "RepoTag=${REPO_TAG}"
      "RepoUrl=${REPO_URL}"
      "RuntimeCapMinutes=${RUNTIME_CAP}"
      "S3Bucket=${BUCKET}"
  )

  if [ "${DRY_RUN}" -eq 1 ]; then
    echo "DRY RUN — no AWS calls made."
    echo
    echo "Resolved deploy command:"
    printf '  %q' "${deploy_cmd[@]}"
    echo
    echo
    echo "Job id:      ${job_id}"
    echo "Stack name:  ${stack_name}"
    echo "Results will appear at: ${s3_prefix}"
    return 0
  fi

  echo "Launching run ${job_id} (stack ${stack_name})..."
  "${deploy_cmd[@]}"
  echo "Launched run ${job_id}."
  echo "Results will appear at: ${s3_prefix}"
}

# ─────────────────────────────────────────────────────────────────────────────
# check <job_id> — list the prefix and presign the report
# ─────────────────────────────────────────────────────────────────────────────
cmd_check() {
  local job_id="${1:-}"; shift || true
  [ -n "${job_id}" ] || die "usage: bench-aws.sh check <job_id> [--bucket <name>]"
  while [ $# -gt 0 ]; do
    case "$1" in
      --bucket) BUCKET="$2"; shift 2 ;;
      *) die "unknown check flag: $1" ;;
    esac
  done
  local prefix="s3://${BUCKET}/runs/${job_id}/"
  echo "Listing ${prefix}"
  aws s3 ls --region "${REGION}" "${prefix}" --recursive || true
  echo
  echo "Presigned report URL (valid 1h):"
  aws s3 presign --region "${REGION}" "${prefix}report.html" --expires-in 3600
}

# ─────────────────────────────────────────────────────────────────────────────
# delete <stack> — convenience wrapper over delete-stack
# ─────────────────────────────────────────────────────────────────────────────
cmd_delete() {
  local stack="${1:-}"
  [ -n "${stack}" ] || die "usage: bench-aws.sh delete <stack-name>"
  echo "Deleting stack ${stack} in ${REGION}..."
  aws cloudformation delete-stack --region "${REGION}" --stack-name "${stack}"
  echo "Delete requested. Track with: aws cloudformation describe-stacks --region ${REGION} --stack-name ${stack}"
}

# ─────────────────────────────────────────────────────────────────────────────
# Dispatch
# ─────────────────────────────────────────────────────────────────────────────
main() {
  local sub="${1:-deploy}"
  case "${sub}" in
    deploy)        shift || true; cmd_deploy "$@" ;;
    check)         shift; cmd_check "$@" ;;
    delete)        shift; cmd_delete "$@" ;;
    -h|--help|help) usage ;;
    --*)           cmd_deploy "$@" ;;   # allow `bench-aws.sh --dry-run ...`
    *)             die "unknown subcommand: ${sub} (expected deploy|check|delete)" ;;
  esac
}

main "$@"
