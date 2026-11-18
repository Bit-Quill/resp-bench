# AGENTS.md

Guidance for coding agents working in this repo.

## What this is

resp-bench is a benchmark harness for RESP (Valkey/Redis) client drivers across
languages. Each language has an engine that runs a JSON workload through one driver
and writes NDJSON metrics. `scripts/run_benchmark_matrix.py` sweeps drivers,
connection counts, and iterations, and `scripts/generate_interactive_graphs.py`
turns a sweep into an HTML report. `infra/` runs a sweep on a throwaway EC2
instance through CloudFormation. That path is how Valkey GLIDE releases get perf
and soak tested.

## Where things are

| Path | Contents |
|---|---|
| `java/`, `ruby/`, `csharp/`, `python/`, `node/`, `go/`, `php/`, `rust/` | One engine per language. Docs: `docs/BENCHMARKS_<LANG>.md`, `<lang>/README.md` |
| `configs/drivers/default/`, `configs/drivers/high-throughput/` | One driver config per `driver_id` (schema: `configs/schemas/driver-config.schema.json`) |
| `configs/workloads/reference/` | Reference workloads: `basic-standalone-single-client-1M-reqs`, `basic-standalone-single-client-10-secs`, `soak-standalone-12h` |
| `configs/matrices/` | Sweeps. Perf/soak matrices follow the naming below |
| `configs/test/` | Fixtures for the script tests |
| `scripts/` | Matrix runner, graph generator, `system_monitor.py` (CPU/RSS sampler), `tests/` |
| `infra/provision.sh` | Cloud-agnostic toolchain install, server build, engine cache warm-up |
| `infra/aws/` | `bench-aws.sh` (driver), `benchmark.yaml` (CloudFormation), `run-remote.sh` (on-instance runner). Details: `infra/README.md` |
| `results/` (gitignored) | Local sweep output: `<OUTPUT_DIR>/<run-id>/`, with `latest` pointing at the newest run |
| `graphs/interactive/` | Default `GRAPHS_DIR` for generated reports. `graphs/<lang>/` holds checked-in PNGs |

`DRIVER_ENGINE_MAP` in `scripts/run_benchmark_matrix.py` maps each `driver_id` to the
engine whose `make <engine>-run` target runs it.

## Makefile targets

- `make server-standalone-start` / `server-standalone-stop`: local Valkey on 6379, bound to `SERVER_BIND` (default `127.0.0.1`).
- `make <lang>-build | <lang>-test | <lang>-info`, and `make <lang>-run DRIVER=<driver.json> WORKLOAD=<workload.json>`.
- `make benchmark-matrix MATRIX=<path> OUTPUT_DIR=<dir> SERVER_HOST=<host>`: run a sweep. Always runs `java-build` first.
- `make benchmark-matrix-dry-run MATRIX=<path>`: print the plan and check that every referenced driver/workload file exists. Needs no server.
- `make benchmark-matrix-graphs OUTPUT_DIR=<dir> [RUN_ID=<id>]`: build the report for a run.
- `make test-scripts`: unit tests for `scripts/`.

The Makefile calls `python`. On hosts with only `python3`, call
`python3 scripts/run_benchmark_matrix.py --matrix <path> --output-dir <dir> --dry-run` directly.

## Running perf and soak tests on AWS

Launching, checking, and deleting runs uses the user's AWS account and costs
money. Do it only when the user asks.

Flow: `bench-aws.sh deploy` creates the stack `resp-bench-<job_id>` in us-east-1
(EC2 instance, security group with no ingress, IAM role). The instance clones
`--repo-tag` from `--repo-url`, runs `provision.sh`, then `run-remote.sh`. That
script schedules the runtime cap, starts Valkey, signals CloudFormation, and runs
`make benchmark-matrix MATRIX=configs/matrices/<name>.json`. It then builds the
report, uploads to `s3://valkey-glide-resp-bench/<prefix>/`, and shuts down
(the instance is set to terminate on shutdown). `deploy` returns once the server
answers PING (CreationPolicy, 45 min timeout). Prerequisite: AWS CLI v2,
authenticated.

```
infra/aws/bench-aws.sh deploy --matrix <name> --instance-type <type> --runtime-cap <min> \
    [--repo-tag <ref>] [--job-id-prefix <p>] [--ruby-client-version <v>]
infra/aws/bench-aws.sh deploy --dry-run --matrix <name> ...   # print the CFN command, no AWS calls
infra/aws/bench-aws.sh check <job_id>                         # list S3 objects, presign report.html (1h)
infra/aws/bench-aws.sh delete resp-bench-<job_id>             # remove the leftover stack shell
```

| Flag | Default | Notes |
|---|---|---|
| `--matrix` | `valkey-glide-basic-multiclients` (smoke) | Name under `configs/matrices/`, no path or `.json`. Always pass it |
| `--instance-type` | `m5.large` | See the table below |
| `--runtime-cap` | `180` (max 1440) | Minutes. `shutdown -h +N` is scheduled when `run-remote.sh` starts, after provisioning |
| `--repo-tag` | `main` | The instance clones this ref, so changes must be pushed to `--repo-url` first |
| `--repo-url` | `https://github.com/Bit-Quill/resp-bench.git` | |
| `--job-id-prefix` | none | Use it to tag non-Ruby runs with the client and version, e.g. `node-2.5.2-perf` |
| `--ruby-client-version` | `1.0.0` | Ruby only. Also files results by version (below) |

Recommended settings:

| Test | Instance type | `--runtime-cap` | Notes |
|---|---|---|---|
| `perf-<client>` | Same type as the baseline run. `infra/README.md` recommends `m5.metal` or `c5.metal` for fidelity | `240` | Ratios are only comparable between runs on the same instance type |
| `soak-<client>`, `soakctl-<client>` | `m5.2xlarge` (8 vCPU; the soak connection counts are sized for it) | `780` | 12h STEADY plus warm-up and upload. Launch the soak and its control at the same time, each on its own instance |

The perf cap is an estimate (24 cells of 2M requests each, plus the Java build), not a measured value.

### Selecting the client version

| Client | What sets the GLIDE version | Value on main |
|---|---|---|
| Ruby | `--ruby-client-version` → `VALKEY_GLIDE_RUBY_VERSION` → exact pin in `ruby/Gemfile` | `1.0.0` |
| C# | `Valkey.Glide` in `csharp/src/RespBench/RespBench.csproj`. Branch `currantw/csharp-1.2-benchmarks` adds `--csharp-client-version` and `--csharp-client-package` | `0.9.0` |
| Java | `valkey-glide.version` in `java/pom.xml` | `2.2.3` |
| Node.js | `@valkey/valkey-glide` in `node/package.json` and `node/package-lock.json` | `2.5.2` |
| Python | `valkey-glide` in `python/pyproject.toml` | `2.5.2` |
| Go | `github.com/valkey-io/valkey-glide/go/v2` in `go/go.mod` (and `go.sum`) | `v2.5.3` |
| Rust | `GLIDE_COMMIT` default in `infra/provision.sh`; `rust/Cargo.toml` depends on that checkout by path | `0b46f055…` |
| PHP | The `valkey_glide` extension installed on the host. No pin in the repo | n/a |

For every client except Ruby: change the pin on a branch, push it to the fork,
and pass `--repo-tag <branch>`. Name the run with `--job-id-prefix`, because
those results land under `runs/<job_id>/`, not under a version folder.

Gap: `provision.sh` does not install Go or PHP (see its "FUTURE ENGINES"
comment), so the `-go` and `-php` matrices fail on AWS. Run them locally with
`make benchmark-matrix` until provisioning is added.

### Results layout

Prefix: `runs/<job_id>/` by default. With `--ruby-client-version` it is
`runs/ruby/<base>/release/<job_id>/` or `runs/ruby/<base>/rc/<base>-<tag>/<job_id>/`.
`deploy` prints the prefix.

```
<prefix>/
  report.html          # Plotly report (RPS, latency, CPU)
  run-metadata.json    # status (succeeded|failed|aborted), sweep_exit_code, matrix, git sha, instance, client versions
  results/             # <label>.ndjson metrics, <label>.system.ndjson CPU/RSS samples, _manifest.json per-cell status
  graphs/
  logs/cloud-init-output.log
```

The upload happens once, at the end, so `check` finds nothing while a run is in
progress. A failed or capped run still uploads what it has.

### Teardown

The instance terminates itself. The stack (security group + IAM role) stays
until you delete it with `bench-aws.sh delete resp-bench-<job_id>`. To list
leftover stacks:

```
aws cloudformation list-stacks --region us-east-1 \
  --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE \
  --query "StackSummaries[?starts_with(StackName, 'resp-bench-')].StackName"
```

## Test definitions

Naming: `perf-<client>` (GLIDE vs a yardstick driver), `soak-<client>` (one 12h
GLIDE cell), `soakctl-<client>` (the same soak with the yardstick, run as a
control). `<client>` is the engine directory name.

| Client | Perf / soak / control matrices | GLIDE driver / yardstick | Perf: drivers, connections, iterations | Soak connections |
|---|---|---|---|---|
| Ruby | `perf-ruby` / `soak-ruby` / none | `valkey-glide-ruby` / `redis-rb` | high-throughput, [1,4,8,16], 3 | 8 (forked workers) |
| C# (pending branch) | `perf-csharp` / `soak-csharp` / `soakctl-csharp` | `valkey-glide-csharp` / `stackexchange-redis` | default, [1,2,4,8,16], 5 | 64 |
| Java | `perf-java` / `soak-java` / `soakctl-java` | `valkey-glide` / `lettuce` | high-throughput, [1,4,8,16], 3 | 64 (virtual threads) |
| Node.js | `perf-node` / `soak-node` / `soakctl-node` | `valkey-glide-node` / `ioredis` | high-throughput, [1,4,8,16], 3 | 64 (one event loop) |
| Python | `perf-python` / `soak-python` / `soakctl-python` | `valkey-glide-python` / `redis-py` | high-throughput, [1,4,8,16], 3 | 64 (one asyncio loop) |
| Go | `perf-go` / `soak-go` / `soakctl-go` | `valkey-glide-go` / `go-redis` | high-throughput, [1,4,8,16], 3 | 64 (goroutines) |
| PHP | `perf-php` / `soak-php` / `soakctl-php` | `valkey-glide-php` / `phpredis` | high-throughput, [1,4,8,16], 3 | 8 (forked workers) |
| Rust | `perf-rust` / `soak-rust` / `soakctl-rust` | `valkey-glide-rust` / `redis-rs` | high-throughput, [1,4,8,16], 3 | 64 (OS threads) |

Soak matrices use high-throughput driver configs and `cpu_interval: 5`. Each
matrix's `description` explains its choices. C# matrices live on
`currantw/csharp-1.2-benchmarks` until it merges.

Workloads:

- `basic-standalone-single-client-1M-reqs` (perf): WARMUP of 1M sequential SETs, then STEADY of 1M requests at 80/20 GET/SET over 1M keys (16 B keys, 512 B values).
- `soak-standalone-12h` (soak): WARMUP of 100K SETs, then 12h STEADY at 80/20 GET/SET over 100K keys (512 B values).

The matrix runner overwrites each phase's `connections` with the matrix value.

## Judging results

There are no numeric pass/fail thresholds yet. Results are judged by comparison.

Perf: for each connection count, compute GLIDE / yardstick for RPS and for p50/p99
latency, from the same run. Compare those ratios with a baseline run of the
previous GLIDE release that used the same matrix, instance type, and repo ref. The
ratio cancels instance-to-instance noise; absolute numbers across runs do not. The
report's delta charts use `spring-data-valkey-glide` as the reference driver unless
`--reference` is passed, so for `perf-<client>` regenerate them locally with
`python scripts/generate_interactive_graphs.py <results-dir> --reference <glide driver_id>`.

Soak: read `memory_rss_bytes` (engine process-group RSS) from
`results/<label>.system.ndjson`. The report does not plot RSS. After warm-up, a flat
RSS series passes and a steady rise over the 12h suggests a leak. Where a control
exists, a rising GLIDE series with a flat control points at GLIDE, and both rising
points at the engine or harness. Throughput and latency for engines without
per-interval reporting (Ruby, C#) are a single 12h aggregate.
