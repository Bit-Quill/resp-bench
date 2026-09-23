<?php

declare(strict_types=1);

namespace RespBench\Engine;

use RespBench\Client\BenchmarkClient;
use RespBench\Client\Factory as ClientFactory;
use RespBench\Command\Factory as CommandFactory;
use RespBench\Config\DriverConfig;
use RespBench\Config\KeyspaceConfig;
use RespBench\Config\PhaseConfig;
use RespBench\Config\WorkloadConfig;
use RespBench\Metrics\Collector;
use RespBench\Metrics\HdrHistogram;
use RespBench\Metrics\NdjsonWriter;
use RuntimeException;

/**
 * Coordinates the benchmark run.
 *
 * Concurrency model: one worker per connection.
 *  - "process" mode (default when ext-pcntl is available): fork one process per
 *    connection. Each worker connects AFTER the fork (never inherits a
 *    connection), runs its slice, and streams partial metrics + its own measured
 *    start/stop back to the parent over a socket pair. The parent merges all
 *    partials and writes NDJSON.
 *  - "inline" mode: run all connections sequentially in one process. Only valid
 *    for the recording driver or connections == 1 — for a real driver with
 *    connections > 1 the serial execution would misreport throughput, so the
 *    engine fails loudly instead (see resolveMode()).
 */
final class Benchmark
{
    private const MAX_WORKERS = 256;

    public const MODE_PROCESS = 'process';
    public const MODE_INLINE = 'inline';

    public function __construct(
        private readonly string $host,
        private readonly int $port,
        private readonly DriverConfig $driverConfig,
        private readonly WorkloadConfig $workloadConfig,
        private readonly string $metricsPath,
        private readonly ?string $commitId = null,
        private readonly ?string $concurrencyMode = null,
    ) {
    }

    public function run(): int
    {
        $this->rejectUnsupportedKnobs();

        $writer = new NdjsonWriter($this->metricsPath);
        $writer->setMetadata(
            commitId: $this->commitId,
            driverId: $this->driverConfig->driverId,
            primaryDriverVersion: $this->probeDriverVersion(),
            secondaryDriverId: $this->driverConfig->secondaryDriverId(),
        );

        $exitStatus = 0;
        foreach ($this->workloadConfig->phases as $phase) {
            [$collector, $status] = $this->runPhase($phase);
            $writer->writePhaseResults($phase->id, $status, $phase->connections, $collector);
            if ($status !== 'COMPLETED') {
                $exitStatus = 1;
            }
        }

        return $exitStatus;
    }

    /**
     * Decide the concurrency mode, validating the requested value and refusing
     * to silently misreport. Returns MODE_PROCESS or MODE_INLINE.
     */
    private function resolveMode(int $connections): string
    {
        $requested = $this->concurrencyMode;
        if ($requested !== null && $requested !== self::MODE_PROCESS && $requested !== self::MODE_INLINE) {
            throw new RuntimeException(
                "Invalid --concurrency '{$requested}'. Use '" . self::MODE_PROCESS
                . "' or '" . self::MODE_INLINE . "'."
            );
        }

        $isRecording = $this->driverConfig->driverId === 'recording';
        $hasPcntl = function_exists('pcntl_fork');

        // Explicit request wins, but a process request without pcntl can't be honored.
        if ($requested === self::MODE_PROCESS) {
            if (!$hasPcntl) {
                throw new RuntimeException(
                    'Requested --concurrency process but ext-pcntl is unavailable '
                    . '(check disable_functions for pcntl_fork).'
                );
            }

            return self::MODE_PROCESS;
        }

        if ($requested === self::MODE_INLINE) {
            // Inline is honest only when it can't misrepresent concurrency.
            if (!$isRecording && $connections > 1) {
                throw new RuntimeException(
                    "Refusing --concurrency inline for driver '{$this->driverConfig->driverId}' "
                    . "with connections={$connections}: serial execution would report "
                    . 'single-connection throughput labelled as N connections. '
                    . 'Use process mode.'
                );
            }

            return self::MODE_INLINE;
        }

        // Auto: recording and single-connection stay inline; everything else needs process.
        if ($isRecording || $connections <= 1) {
            return self::MODE_INLINE;
        }

        if (!$hasPcntl) {
            throw new RuntimeException(
                "Driver '{$this->driverConfig->driverId}' with connections={$connections} "
                . 'requires ext-pcntl for process-per-connection concurrency, but pcntl_fork '
                . 'is unavailable (check disable_functions). Refusing to run serially and '
                . 'misreport throughput.'
            );
        }

        return self::MODE_PROCESS;
    }

    /**
     * @return array{0: Collector, 1: string} the merged collector and phase status
     */
    private function runPhase(PhaseConfig $phase): array
    {
        $workerCount = max(1, min($phase->connections, self::MAX_WORKERS));
        $mode = $this->resolveMode($phase->connections);

        $collector = new Collector();

        if ($mode === self::MODE_PROCESS) {
            $status = $this->runPhaseMultiProcess($phase, $workerCount, $collector);
        } else {
            $status = $this->runPhaseInline($phase, $workerCount, $collector);
        }

        return [$collector, $status];
    }

    // --- Multi-process execution -------------------------------------------

    private function runPhaseMultiProcess(PhaseConfig $phase, int $workerCount, Collector $collector): string
    {
        /** @var array<int,resource> $parentEnds */
        $parentEnds = [];
        /** @var array<int,int> $children pid => worker index */
        $children = [];

        try {
            for ($i = 0; $i < $workerCount; $i++) {
                $pair = stream_socket_pair(STREAM_PF_UNIX, STREAM_SOCK_STREAM, STREAM_IPPROTO_IP);
                if ($pair === false) {
                    throw new RuntimeException('stream_socket_pair failed');
                }
                [$parentEnd, $childEnd] = $pair;

                $pid = pcntl_fork();
                if ($pid === -1) {
                    fclose($parentEnd);
                    fclose($childEnd);
                    throw new RuntimeException('pcntl_fork failed');
                }

                if ($pid === 0) {
                    // CHILD: everything here is isolated. Any failure must exit
                    // non-zero and never fall through into the parent's code.
                    fclose($parentEnd);
                    $code = 0;
                    try {
                        $this->runWorkerAndReport($phase, $workerCount, $i, $childEnd);
                    } catch (\Throwable $e) {
                        // Report the failure to the parent, then exit non-zero.
                        @fwrite($childEnd, $this->serializeError($e) . "\n");
                        $code = 1;
                    } finally {
                        @fclose($childEnd);
                    }
                    exit($code);
                }

                fclose($childEnd);
                $parentEnds[$i] = $parentEnd;
                $children[$pid] = $i;
            }

            // Collect partial metrics from each worker.
            $workerErrors = 0;
            foreach ($parentEnds as $fh) {
                $payload = stream_get_contents($fh);
                if ($payload === false || trim((string) $payload) === '') {
                    // A worker that wrote nothing died before reporting.
                    $workerErrors++;
                    continue;
                }
                if (!$this->mergePartial($collector, (string) $payload)) {
                    $workerErrors++;
                }
            }
        } finally {
            // Always close parent ends and reap every child, even on failure,
            // so no worker is left orphaned driving load against the server.
            foreach ($parentEnds as $fh) {
                if (is_resource($fh)) {
                    @fclose($fh);
                }
            }
            foreach (array_keys($children) as $pid) {
                $status = 0;
                pcntl_waitpid($pid, $status);
                if (!(pcntl_wifexited($status) && pcntl_wexitstatus($status) === 0)) {
                    $workerErrors = ($workerErrors ?? 0) + 1;
                }
            }
        }

        // Any worker that failed to run or exited non-zero makes the phase an ERROR.
        return ($workerErrors ?? 0) > 0 ? 'ERROR' : 'COMPLETED';
    }

    /**
     * @param resource $childEnd
     */
    private function runWorkerAndReport(PhaseConfig $phase, int $workerCount, int $workerIndex, $childEnd): void
    {
        $workerCollector = $this->runWorker($phase, $workerCount, $workerIndex);
        fwrite($childEnd, $this->serializeCollector($workerCollector));
    }

    // --- Inline execution (recording / single-connection only) -------------

    private function runPhaseInline(PhaseConfig $phase, int $workerCount, Collector $collector): string
    {
        for ($i = 0; $i < $workerCount; $i++) {
            $workerCollector = $this->runWorker($phase, $workerCount, $i);
            $collector->mergeFrom($workerCollector);
        }

        return 'COMPLETED';
    }

    // --- Shared worker loop ------------------------------------------------

    private function runWorker(PhaseConfig $phase, int $workerCount, int $workerIndex): Collector
    {
        $collector = new Collector();

        $client = ClientFactory::createAndConnect($this->host, $this->port, $this->driverConfig);
        try {
            $commands = CommandFactory::createAll($phase->commands);
            $selector = new CommandSelector($commands);
            $keyGen = KeyGenerator::forWorker($phase->keyspace, $workerIndex, $workerCount);

            $rps = $this->workerRps($phase, $workerCount, $workerIndex);

            // A worker whose rps share rounded down to 0 does no work at all —
            // no limiter, no requests. Running it unthrottled (or at a floored
            // 1 rps) would push the aggregate above the target.
            if ($phase->hasRpsLimit() && $rps === 0) {
                $collector->start();
                $collector->stop();

                return $collector;
            }

            $limiter = RateLimiter::create($rps);

            // Warmup runs BEFORE the metrics clock starts (matches Java/Ruby/C#/Node),
            // and uses PING so it does not consume from the workload key sequence.
            $this->runWarmup($client, $phase->warmupRequests, $workerCount, $workerIndex);

            // Start the clock only now — after connect and warmup — so duration_ms
            // reflects the workload window, not fork/connect/warmup overhead.
            $collector->start();

            $target = $this->workerRequestTarget($phase, $workerCount, $workerIndex);
            $deadline = $phase->completion->isDurationBased()
                ? microtime(true) + $phase->completion->durationSeconds()
                : null;

            $done = 0;
            while (true) {
                if ($target !== null && $done >= $target) {
                    break;
                }
                if ($deadline !== null && microtime(true) >= $deadline) {
                    break;
                }

                $limiter?->acquire();
                $command = $selector->select();
                $collector->record($command->execute($client, $keyGen));
                $done++;
            }

            $collector->stop();
        } finally {
            $client->close();
        }

        return $collector;
    }

    private function runWarmup(
        BenchmarkClient $client,
        int $warmupRequests,
        int $workerCount,
        int $workerIndex,
    ): void {
        if ($warmupRequests <= 0) {
            return;
        }
        $share = $this->splitShare($warmupRequests, $workerCount, $workerIndex);
        for ($i = 0; $i < $share; $i++) {
            // PING only — do not perturb the workload key sequence.
            $client->ping();
        }
    }

    /**
     * Number of workers that actually do work in this phase. When an rps limit is
     * lower than the connection count, only `rps_limit` workers run (each at 1
     * rps) — otherwise flooring/duplicating would push the aggregate over target.
     * The idle workers do nothing. Without an rps limit every worker is active.
     */
    private function activeWorkerCount(PhaseConfig $phase, int $workerCount): int
    {
        if ($phase->hasRpsLimit()) {
            return max(1, min($workerCount, $phase->rpsLimit));
        }

        return $workerCount;
    }

    /**
     * Per-worker RPS. The phase rps limit is split across the ACTIVE workers with
     * the remainder distributed; idle workers (index >= active) get 0.
     */
    private function workerRps(PhaseConfig $phase, int $workerCount, int $workerIndex): int
    {
        if (!$phase->hasRpsLimit()) {
            return -1; // unlimited
        }

        $active = $this->activeWorkerCount($phase, $workerCount);
        if ($workerIndex >= $active) {
            return 0;
        }

        return $this->splitShare($phase->rpsLimit, $active, $workerIndex);
    }

    /**
     * Split $total across $workerCount, giving the first ($total % $workerCount)
     * workers one extra. Deterministic and sums exactly to $total.
     */
    private function splitShare(int $total, int $workerCount, int $workerIndex): int
    {
        $base = intdiv($total, $workerCount);
        if ($workerIndex < ($total % $workerCount)) {
            $base++;
        }

        return $base;
    }

    /**
     * Per-worker request target for a request-based phase. The budget is split
     * across the ACTIVE workers so the shares sum exactly to total_requests even
     * when some workers are idle (rps_limit < connections). Idle workers get 0.
     */
    private function workerRequestTarget(PhaseConfig $phase, int $workerCount, int $workerIndex): ?int
    {
        if (!$phase->completion->isRequestBased()) {
            return null;
        }

        $active = $this->activeWorkerCount($phase, $workerCount);
        if ($workerIndex >= $active) {
            return 0;
        }

        return $this->splitShare($phase->completion->totalRequests(), $active, $workerIndex);
    }

    // --- Cross-process metrics serialization -------------------------------

    /**
     * Serialize a collector's totals, per-command counts + histogram counts, and
     * its own measured start/stop, so the parent can reconstruct histograms
     * losslessly and compute the true phase window as min(start)/max(stop).
     */
    private function serializeCollector(Collector $collector): string
    {
        $commands = [];
        foreach ($collector->allMetrics() as $name => $cmd) {
            $histogram = $cmd->histogram();
            $counts = [];
            if ($histogram !== null) {
                $len = $histogram->relevantLength();
                for ($i = 0; $i < $len; $i++) {
                    $c = $histogram->rawCountAt($i);
                    if ($c > 0) {
                        $counts[$i] = $c;
                    }
                }
            }
            $commands[$name] = [
                'requests' => $cmd->requests(),
                'errors' => $cmd->errors(),
                'counts' => $counts,
            ];
        }

        return json_encode([
            'ok' => true,
            'total_requests' => $collector->totalRequests(),
            'total_errors' => $collector->totalErrors(),
            'start' => $collector->startTime(),
            'stop' => $collector->endTime(),
            'commands' => $commands,
        ], JSON_THROW_ON_ERROR) . "\n";
    }

    private function serializeError(\Throwable $e): string
    {
        return json_encode(['ok' => false, 'error' => $e->getMessage()], JSON_THROW_ON_ERROR);
    }

    /**
     * Merge a worker's payload into the parent collector.
     *
     * @return bool true if the worker reported success, false if it reported an error
     */
    private function mergePartial(Collector $collector, string $payload): bool
    {
        $line = trim($payload);
        if ($line === '') {
            return false;
        }

        try {
            /** @var array<string,mixed> $data */
            $data = json_decode($line, true, 512, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return false;
        }

        if (($data['ok'] ?? false) !== true) {
            return false;
        }

        $partial = new Collector();
        /** @var array<string,array{requests:int,errors:int,counts:array<int,int>}> $cmds */
        $cmds = $data['commands'] ?? [];
        foreach ($cmds as $name => $cmd) {
            $histogram = new HdrHistogram(1, 600_000_000, 3);
            foreach (($cmd['counts'] ?? []) as $index => $count) {
                $histogram->recordValueWithCount($histogram->valueFromIndex((int) $index), (int) $count);
            }
            $partial->ingestCommand((string) $name, (int) $cmd['requests'], (int) $cmd['errors'], $histogram);
        }
        $partial->ingestTotals((int) ($data['total_requests'] ?? 0), (int) ($data['total_errors'] ?? 0));

        // Phase window = min worker start .. max worker stop (true concurrent window).
        if (isset($data['start']) && $data['start'] !== null) {
            $start = (float) $data['start'];
            if ($collector->startTime() === null || $start < $collector->startTime()) {
                $collector->setStartTime($start);
            }
        }
        if (isset($data['stop']) && $data['stop'] !== null) {
            $stop = (float) $data['stop'];
            if ($collector->endTime() === null || $stop > $collector->endTime()) {
                $collector->setEndTime($stop);
            }
        }

        $collector->mergeFrom($partial);

        return true;
    }

    private function probeDriverVersion(): string
    {
        try {
            $client = ClientFactory::create((string) $this->driverConfig->driverId);

            return $client->driverVersion();
        } catch (\Throwable) {
            return 'unknown';
        }
    }

    /**
     * Fail loudly if a config sets a knob this engine does not honor, so a
     * non-comparable run errors out instead of silently producing a number that
     * looks valid. Java honors pipeline_depth > 1 and cps_limit; the PHP engine
     * does not (yet), and the PHP clients do not apply command_timeout_ms.
     */
    private function rejectUnsupportedKnobs(): void
    {
        foreach ($this->workloadConfig->phases as $phase) {
            if ($phase->effectivePipelineDepth() > 1) {
                throw new RuntimeException(
                    "Phase '{$phase->id}' sets pipeline_depth={$phase->pipelineDepth}, "
                    . 'which the PHP engine does not implement (it would silently run at '
                    . 'depth 1 and be non-comparable to engines that honor it). '
                    . 'Remove the knob or use an engine that supports it.'
                );
            }
            if ($phase->hasCpsLimit()) {
                throw new RuntimeException(
                    "Phase '{$phase->id}' sets cps_limit={$phase->cpsLimit}, "
                    . 'which the PHP engine does not implement (connection-rate limiting '
                    . 'is unenforced here). Remove the knob or use an engine that supports it.'
                );
            }
        }

        $timeout = $this->driverConfig->specificDriverConfig['command_timeout_ms'] ?? null;
        if ($timeout !== null) {
            throw new RuntimeException(
                'Driver config sets command_timeout_ms, which the PHP clients do not '
                . 'apply. Remove the knob to avoid a silently non-comparable run.'
            );
        }
    }
}
