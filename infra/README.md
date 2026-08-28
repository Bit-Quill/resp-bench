# resp-bench infrastructure — fire-and-forget benchmarking on AWS

Launch a benchmark sweep from your laptop, close the lid, and read the report
in S3 later. One command stands up a stock Amazon Linux 2023 EC2 instance that
provisions its own toolchain, runs the sweep, publishes the results to S3, and
then **terminates itself**. The only thing left behind is a free stack shell (a
security group + an IAM role), which you remove with a single `delete-stack`.

## Layout

```
infra/
├── provision.sh          # cloud-agnostic toolchain install + engine build + cache warm-up
├── aws/
│   ├── benchmark.yaml     # CloudFormation: EC2 + SG + IAM role/profile + UserData + CreationPolicy
│   ├── run-remote.sh      # on-instance job runner (server → signal → sweep → report → S3 → self-terminate)
│   └── bench-aws.sh       # thin driver: generate job id → deploy → print S3 prefix → exit
└── README.md
```

### Architecture split: cloud-agnostic recipe vs. AWS control plane

The design deliberately separates *what gets installed inside the VM* from *how
the VM is launched, gated, and torn down*:

| Concern | Lives in | Cloud-specific? |
|---|---|---|
| Toolchain install, server build, cache warm-up | `provision.sh` | **No** — pure `install + build`, inputs via env vars, `dnf`/`apt` shim. A future GCP/Azure control plane reuses it unchanged. |
| Launch / readiness gate / teardown | `aws/benchmark.yaml` (CloudFormation) | Yes |
| Server start, `cfn-signal`, sweep, S3 upload, self-terminate | `aws/run-remote.sh` | Yes (IMDS, S3, cfn-signal) |
| Job id + `deploy` + print prefix | `aws/bench-aws.sh` | Yes |

`provision.sh` contains **no** AWS/CloudFormation/S3/IMDS calls — that is the
invariant that keeps it portable. All AWS plumbing lives under `aws/`.

## Prerequisites

- The AWS CLI (v2), authenticated to the target account. No Terraform, CDK,
  Docker, or Packer needed — CloudFormation is driven through the CLI.
- Everything runs in **us-east-1** (pinned in every script); the destination
  bucket `valkey-glide-resp-bench` lives there.
- The instance provisions on a **stock AL2023 x86_64 AMI** resolved at deploy
  time from the public SSM parameter
  `/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64` — no
  baked AMI, no hardcoded image id.

## Usage

Launch a smoke run (smallest shipped matrix) and walk away:

```
$ infra/aws/bench-aws.sh deploy --matrix valkey-glide-basic-multiclients
Launching run bench-20260825-143002-a1b9c3 (stack resp-bench-bench-20260825-143002-a1b9c3)...
...
Launched run bench-20260825-143002-a1b9c3.
Results will appear at: s3://valkey-glide-resp-bench/runs/bench-20260825-143002-a1b9c3/
$
```

`deploy` blocks only until provisioning is **genuinely healthy** — a
`CreationPolicy` + `cfn-signal` gate makes stack creation wait for the server to
answer `PING` (and roll back if it never does), rather than reporting success
the moment the instance exists. Once that gate clears, the instance runs the
sweep, uploads, and self-terminates on its own; your machine is out of the loop.

### Preview without touching AWS

```
$ infra/aws/bench-aws.sh deploy --dry-run --matrix valkey-glide-basic-multiclients
DRY RUN — no AWS calls made.

Resolved deploy command:
  aws cloudformation deploy --region us-east-1 --stack-name ... --parameter-overrides ...

Job id:      bench-...
Stack name:  resp-bench-bench-...
Results will appear at: s3://valkey-glide-resp-bench/runs/bench-.../
```

### Cost guardrails

- The default matrix is the **smallest shipped one** (a smoke run). Large sweeps
  (e.g. `driver-comparison-high-tps` = 720 cells) require passing `--matrix`
  explicitly, so nobody launches a huge bill by reflex.
- The default instance type (`m5.large`) is small and cheap. For a
  fidelity-grade sweep pass `--instance-type m5.metal` (or `c5.metal`) — and
  note that comparable numbers require pinning the CPU model.
- Runs use **on-demand** instances (a spot reclamation at 4am would kill an
  unattended sweep).
- A hard **runtime cap** (`--runtime-cap <minutes>`, default 180) schedules
  `shutdown -h +N` at boot so a hung run can't bill indefinitely.

### Larger sweep example

```
infra/aws/bench-aws.sh deploy \
  --matrix driver-comparison-defaults \
  --instance-type m5.metal \
  --repo-tag v0.1.0-phase0 \
  --runtime-cap 360
```

> **Repo tag / known dependency.** The automation assumes the pinned
> `--repo-tag` includes the Phase-0 correctness fixes (fail-loud runner, honest
> exit code, CLI-based flush). On raw `main` the sweep may fail at the flush
> step (the runner calls `redis-cli`, which isn't installed — the server ships
> `valkey-cli`). That is expected and out of scope here: the run still uploads a
> diagnosable bundle and self-terminates.

## What lands in S3

```
runs/<job_id>/
  report.html          # interactive Plotly report (scalability + delta)
  run-metadata.json    # job id, status, matrix, repo tag + git sha, Valkey
                       #   version, instance type / id / AZ / AMI, region, timing
  results/             # raw sweep output: *.ndjson, *.system.ndjson, _manifest.json
  graphs/              # generated graph assets
  logs/                # cloud-init-output.log (provision + run log)
```

`job_id = [prefix-]bench-YYYYMMDD-HHMMSS-<6 random>`. The `results/_manifest.json`
is the per-cell status manifest; it is uploaded **even when the sweep fails**
(the run script wraps upload + self-terminate in a `trap ... EXIT`), so a failed
run leaves a diagnosable result in S3 rather than a hung box.

## Cleaning up

Two distinct things exist after a run — handle them separately.

### 1. The compute (handled automatically)

The instance **terminates itself** when the run ends — whether the sweep passed
or failed, and when the runtime cap fires. Compute and its EBS volume are gone
($0) with no action from you, and with **no `ec2:TerminateInstances`
permission** on the instance role (it self-terminates via
`InstanceInitiatedShutdownBehavior: terminate` + `shutdown -h now`).

### 2. The free stack shell (you remove it)

After the instance self-terminates, the CloudFormation stack still exists as a
free shell — the security group and the IAM role. It costs nothing, but you
should remove it once you've read the results:

```
aws cloudformation delete-stack --region us-east-1 --stack-name resp-bench-<job_id>
```

or the convenience wrapper:

```
infra/aws/bench-aws.sh delete resp-bench-<job_id>
```

> There is intentionally **no watchdog Lambda** or auto-teardown. `delete-stack`
> is a deliberate, documented manual step.

To list every leftover shell:

```
aws cloudformation list-stacks --region us-east-1 \
  --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE \
  --query "StackSummaries[?starts_with(StackName, 'resp-bench-')].StackName"
```

## Viewing the report

The bucket blocks public access (account-wide), so use a **presigned URL**:

```
aws s3 presign --region us-east-1 \
  s3://valkey-glide-resp-bench/runs/<job_id>/report.html --expires-in 3600
```

or:

```
infra/aws/bench-aws.sh check <job_id>
```

which lists the run's objects and prints a 1-hour presigned URL for the report.

## Testing `provision.sh` in isolation

Because `provision.sh` is cloud-agnostic and parameterised by env vars, you can
run it on any Linux host (it detects `dnf` vs `apt`):

```
REPO_DIR=/path/to/resp-bench bash infra/provision.sh
```

It installs JDK 21 + Maven, Ruby + bundler, the .NET SDK, Python + pinned deps,
builds the Valkey server, and warms the Java/Ruby/C# build caches. `SKIP_SERVER_BUILD=1`
and `SKIP_WARM_CACHES=1` skip the slow steps for quick smoke checks.
