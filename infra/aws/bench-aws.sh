#!/usr/bin/env bash
#
# bench-aws.sh — thin fire-and-forget driver for the resp-bench AWS runner.
#
# Generates a job id, deploys the CloudFormation stack (which provisions an
# EC2 box, runs the sweep, uploads to S3, and self-terminates), prints the S3
# prefix where results will appear, and exits. No polling loop.
#
# S3 layout: s3://<bucket>/runs/<job_id>/ by default. When
# --ruby-client-version is passed explicitly, results are filed by Ruby GLIDE
# version instead: s3://<bucket>/runs/ruby/<base>/<rc|release>/<job_id>/
# (base = leading numeric part, e.g. 1.1.0; any letter in the version means a
# RubyGems prerelease -> rc).
#
# Region is PINNED to us-east-1 for every AWS call — never inherited from the
# ambient shell (which may export AWS_REGION=us-west-2). The S3 bucket lives in
# us-east-1.
#
# Subcommands:
#   deploy [flags]          Launch a run (default).
#   check  <job_id|prefix> [flags]
#                           List the run's S3 prefix + presign the report.
#                           A bare job id is looked up under runs/ and runs/ruby/.
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
#   --ruby-client-version <v>
#                           Exact valkey-glide-rb gem version the instance
#                           installs (default: 1.0.0). Accepts RubyGems
#                           prerelease forms such as 1.1.0.pre.rc2. Only
#                           affects matrices that select valkey-glide-ruby.
#                           Passing it also files results under
#                           runs/ruby/<base>/<rc|release>/, and a prerelease
#                           tag (e.g. rc2) is added to the job id.
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
# Reproducible default: matches the historical ruby/Gemfile pin.
RUBY_CLIENT_VERSION="1.0.0"
RUBY_CLIENT_VERSION_SET=0   # 1 when --ruby-client-version was passed
DRY_RUN=0

# Safe gem-version characters only: dot/hyphen-separated alphanumeric segments.
# Accepts 1.0.0 and 1.1.0.pre.rc2; rejects spaces, quotes, shell
# metacharacters, and slashes. Must stay in sync with the RubyClientVersion
# AllowedPattern in benchmark.yaml.
readonly RUBY_CLIENT_VERSION_PATTERN='^[0-9A-Za-z]+([.-][0-9A-Za-z]+)*$'

die() { printf 'bench-aws: %s\n' "$*" >&2; exit 1; }

# Print the header comment block (line 2 up to the first non-comment line).
usage() { awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"; }

# require_value <flag> <argc> — fail cleanly when a flag is missing its value
# (instead of an unbound-variable error under `set -u`).
require_value() { [ "$2" -ge 2 ] || die "$1 requires a value"; }

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
# Ruby GLIDE version -> S3 layout pieces (input already pattern-validated)
#   ruby_base_version 1.1.0.pre.rc2 -> 1.1.0   (leading numeric segments)
#   ruby_channel      1.1.0.pre.rc2 -> rc      (any letter = prerelease)
#   ruby_prerelease_tag 1.1.0.pre.rc2 -> rc2   ("" for releases)
# ─────────────────────────────────────────────────────────────────────────────
ruby_base_version() {
  # Always exit 0 (callers use $(...) under `set -e`); empty output = no match.
  if [[ "$1" =~ ^([0-9]+(\.[0-9]+)*) ]]; then printf '%s' "${BASH_REMATCH[1]}"; fi
}
ruby_channel() {
  if [[ "$1" =~ [A-Za-z] ]]; then printf 'rc'; else printf 'release'; fi
}
ruby_prerelease_tag() {
  local rest="${1#"$(ruby_base_version "$1")"}"
  rest="${rest#[.-]}"          # drop the separator after the base version
  rest="${rest#pre[.-]}"       # RubyGems spells "-rc2" as ".pre.rc2"
  printf '%s' "${rest//./-}"
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
      --ruby-client-version)
        require_value "$1" "$#"; RUBY_CLIENT_VERSION="$2"; RUBY_CLIENT_VERSION_SET=1; shift 2 ;;
      --dry-run)       DRY_RUN=1; shift ;;
      -h|--help)       usage; exit 0 ;;
      *) die "unknown deploy flag: $1" ;;
    esac
  done

  # Validate before any AWS call (dry runs included).
  [[ "${RUBY_CLIENT_VERSION}" =~ ${RUBY_CLIENT_VERSION_PATTERN} ]] \
    || die "invalid --ruby-client-version '${RUBY_CLIENT_VERSION}' (expected e.g. 1.0.0 or 1.1.0.pre.rc2)"

  [ -f "${TEMPLATE}" ] || die "template not found: ${TEMPLATE}"

  # File explicitly versioned Ruby GLIDE runs by version; everything else keeps
  # runs/<job_id>/. Must stay under runs/: the instance role may only write
  # runs/* (benchmark.yaml), and the ResultsPrefix AllowedPattern enforces it.
  local ruby_layout="" ruby_tag=""
  if [ "${RUBY_CLIENT_VERSION_SET}" -eq 1 ]; then
    local base channel
    base="$(ruby_base_version "${RUBY_CLIENT_VERSION}")"
    [ -n "${base}" ] || die "--ruby-client-version '${RUBY_CLIENT_VERSION}' must start with a numeric version (e.g. 1.1.0.pre.rc2)"
    channel="$(ruby_channel "${RUBY_CLIENT_VERSION}")"
    ruby_layout="runs/ruby/${base}/${channel}"
    # rc1 and rc2 share runs/ruby/<base>/rc/, so put the tag in the job id
    # unless the caller's prefix already carries it.
    ruby_tag="$(ruby_prerelease_tag "${RUBY_CLIENT_VERSION}")"
    if [ -n "${ruby_tag}" ] && [[ "${JOB_ID_PREFIX}" != *"${ruby_tag}"* ]]; then
      JOB_ID_PREFIX="${JOB_ID_PREFIX:+${JOB_ID_PREFIX}-}${ruby_tag}"
    fi
  fi

  local job_id stack_name results_prefix s3_prefix
  job_id="$(generate_job_id)"
  stack_name="resp-bench-${job_id}"
  results_prefix="${ruby_layout:-runs}/${job_id}"
  s3_prefix="s3://${BUCKET}/${results_prefix}/"

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
      "RubyClientVersion=${RUBY_CLIENT_VERSION}"
      "ResultsPrefix=${results_prefix}"
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
    echo "Ruby GLIDE:  valkey-glide-rb ${RUBY_CLIENT_VERSION}"
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
  local target="${1:-}"; shift || true
  [ -n "${target}" ] || die "usage: bench-aws.sh check <job_id|runs/...|s3://...> [--bucket <name>]"
  while [ $# -gt 0 ]; do
    case "$1" in
      --bucket) BUCKET="$2"; shift 2 ;;
      *) die "unknown check flag: $1" ;;
    esac
  done
  local prefix found
  case "${target}" in
    s3://*) prefix="${target%/}/" ;;                   # full S3 prefix
    */*)    prefix="s3://${BUCKET}/${target%/}/" ;;    # key prefix, e.g. runs/ruby/1.1.0/rc/<job_id>
    *)
      # Bare job id: the default layout first, then the Ruby version layout.
      prefix="s3://${BUCKET}/runs/${target}/"
      if [ -z "$(aws s3 ls --region "${REGION}" "${prefix}" 2>/dev/null)" ]; then
        found="$(aws s3 ls --region "${REGION}" --recursive "s3://${BUCKET}/runs/ruby/" 2>/dev/null \
          | awk '{print $4}' | grep -F -m1 "/${target}/" || true)"
        [ -n "${found}" ] && prefix="s3://${BUCKET}/${found%%/"${target}"/*}/${target}/"
      fi
      ;;
  esac
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
