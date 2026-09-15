<?php

declare(strict_types=1);

namespace RespBench\Engine;

use RespBench\Client\BenchmarkClient;
use RespBench\Client\Factory as ClientFactory;
use RespBench\Command\Command;
use RespBench\Command\Factory as CommandFactory;
use RespBench\Config\DriverConfig;
use RespBench\Config\KeyspaceConfig;
use RespBench\Config\PhaseConfig;
use RespBench\Config\WorkloadConfig;
use RespBench\Metrics\Collector;
use RespBench\Metrics\HdrHistogram;
use RespBench\Metrics\NdjsonWriter;

/**
 * Coordinates the benchmark run.
 *
 * Concurrency model: one worker per connection.
 *  - "process" mode (default when ext-pcntl is available): fork one process per
 *    connection. Each worker connects AFTER the fork (never inherits a
 *    connection), runs its slice, and streams partial metrics back to the parent
 *    over a socket pair. The parent merges all partials and writes NDJSON.
 *  - "inline" mode (fallback / recording driver): run all connections
 *    sequentially in one process. Faithful concurrency isn't possible with a
 *    blocking client here, but it exercises the full pipeline for tests.
 */
final class Benchmark
{
    private const MAX_WORKERS = 256;

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

    public function run(): void
    {
        $writer = new NdjsonWriter($this->metricsPath);
        $writer->setMetadata(
            commitId: $this->commitId,
            driverId: $this->driverConfig->driverId,
            primaryDriverVersion: $this->probeDriverVersion(),
            secondaryDriverId: $this->driverConfig->secondaryDriverId(),
        );

        foreach ($this->workloadConfig->phases as $phase) {
            $collector = $this->runPhase($phase);
            $writer->writePhaseResults($phase->id, 'COMPLETED', $phase->connections, $collector);
        }
    }

    private function mode(): string
    {
        if ($this->concurrencyMode !== null) {
            return $this->concurrencyMode;
        }

        // Recording driver is server-free and cheap — run inline.
        if ($this->driverConfig->driverId === 'recording') {
            return 'inline';
        }

        return function_exists('pcntl_fork') ? 'process' : 'inline';
    }

    private function runPhase(PhaseConfig $phase): Collector
    {
        $collector = new Collector();
        $collector->start();

        $workerCount = max(1, min($phase->connections, self::MAX_WORKERS));

        if ($this->mode() === 'process' && function_exists('pcntl_fork')) {
            $this->runPhaseMultiProcess($phase, $workerCount, $collector);
        } else {
            $this->runPhaseInline($phase, $workerCount, $collector);
        }

        $collector->stop();

        return $collector;
    }

    // --- Multi-process execution -------------------------------------------

    private function runPhaseMultiProcess(PhaseConfig $phase, int $workerCount, Collector $collector): void
    {
        /** @var array<int,resource> $parentEnds */
        $parentEnds = [];
        /** @var array<int,int> $children pid => worker index */
        $children = [];

        for ($i = 0; $i < $workerCount; $i++) {
            $pair = stream_socket_pair(STREAM_PF_UNIX, STREAM_SOCK_STREAM, STREAM_IPPROTO_IP);
            if ($pair === false) {
                throw new \RuntimeException('stream_socket_pair failed');
            }
            [$parentEnd, $childEnd] = $pair;

            $pid = pcntl_fork();
            if ($pid === -1) {
                throw new \RuntimeException('pcntl_fork failed');
            }

            if ($pid === 0) {
                // CHILD: connect after fork, run slice, write partial, exit.
                fclose($parentEnd);
                $this->runWorkerAndReport($phase, $workerCount, $i, $childEnd);
                fclose($childEnd);
                exit(0);
            }

            fclose($childEnd);
            $parentEnds[$i] = $parentEnd;
            $children[$pid] = $i;
        }

        // Collect partial metrics from each worker.
        foreach ($parentEnds as $fh) {
            $payload = stream_get_contents($fh);
            fclose($fh);
            if ($payload === false || $payload === '') {
                continue;
            }
            $this->mergePartial($collector, $payload);
        }

        // Reap children.
        foreach (array_keys($children) as $pid) {
            $status = 0;
            pcntl_waitpid($pid, $status);
        }
    }

    /**
     * @param resource $childEnd
     */
    private function runWorkerAndReport(PhaseConfig $phase, int $workerCount, int $workerIndex, $childEnd): void
    {
        $workerCollector = $this->runWorker($phase, $workerCount, $workerIndex);
        fwrite($childEnd, $this->serializeCollector($workerCollector));
    }

    // --- Inline execution (fallback / recording) ---------------------------

    private function runPhaseInline(PhaseConfig $phase, int $workerCount, Collector $collector): void
    {
        for ($i = 0; $i < $workerCount; $i++) {
            $workerCollector = $this->runWorker($phase, $workerCount, $i);
            $collector->mergeFrom($workerCollector);
        }
    }

    // --- Shared worker loop ------------------------------------------------

    private function runWorker(PhaseConfig $phase, int $workerCount, int $workerIndex): Collector
    {
        $collector = new Collector();

        $client = ClientFactory::createAndConnect($this->host, $this->port, $this->driverConfig);
        try {
            $commands = CommandFactory::createAll($phase->commands);
            $selector = new CommandSelector($commands);
            $keyGen = $this->keyGeneratorForWorker($phase->keyspace, $workerIndex);

            // Divide the phase-level rate limit across workers.
            $rps = $phase->hasRpsLimit() ? max(1, intdiv($phase->rpsLimit, $workerCount)) : -1;
            $limiter = RateLimiter::create($rps);

            $this->runWarmup($client, $commands, $keyGen, $phase->warmupRequests, $workerCount, $workerIndex);

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
        } finally {
            $client->close();
        }

        return $collector;
    }

    private function runWarmup(
        BenchmarkClient $client,
        array $commands,
        KeyGenerator $keyGen,
        int $warmupRequests,
        int $workerCount,
        int $workerIndex,
    ): void {
        if ($warmupRequests <= 0 || $commands === []) {
            return;
        }
        $share = intdiv($warmupRequests, $workerCount);
        if ($workerIndex < ($warmupRequests % $workerCount)) {
            $share++;
        }
        $selector = new CommandSelector($commands);
        for ($i = 0; $i < $share; $i++) {
            $selector->select()->execute($client, $keyGen);
        }
    }

    /**
     * Per-worker request target for request-based completion, split evenly with
     * the remainder distributed to the first workers (matches Java's forkForThread).
     */
    private function workerRequestTarget(PhaseConfig $phase, int $workerCount, int $workerIndex): ?int
    {
        if (!$phase->completion->isRequestBased()) {
            return null;
        }
        $total = $phase->completion->totalRequests();
        $share = intdiv($total, $workerCount);
        if ($workerIndex < ($total % $workerCount)) {
            $share++;
        }

        return $share;
    }

    /**
     * uniform_rand: seed per worker (seed + index) for reproducible-yet-distinct
     * sequences. sequential_int: shared config (each worker walks the keyspace).
     */
    private function keyGeneratorForWorker(KeyspaceConfig $keyspace, int $workerIndex): KeyGenerator
    {
        if ($keyspace->isUniformRand()) {
            return KeyGenerator::createWithSeed($keyspace, $keyspace->seedValue() + $workerIndex);
        }

        return KeyGenerator::create($keyspace);
    }

    // --- Cross-process metrics serialization -------------------------------

    /**
     * Serialize a collector's totals + per-command counts + histogram counts to
     * JSON. Histograms are sent as sparse index=>count maps so the parent can
     * reconstruct an identical HdrHistogram and merge losslessly.
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
            'total_requests' => $collector->totalRequests(),
            'total_errors' => $collector->totalErrors(),
            'commands' => $commands,
        ], JSON_THROW_ON_ERROR) . "\n";
    }

    private function mergePartial(Collector $collector, string $payload): void
    {
        // A worker writes a single JSON line; guard against partial reads.
        $line = trim($payload);
        if ($line === '') {
            return;
        }

        /** @var array{total_requests:int,total_errors:int,commands:array<string,array{requests:int,errors:int,counts:array<int,int>}>} $data */
        $data = json_decode($line, true, 512, JSON_THROW_ON_ERROR);

        $partial = new Collector();
        // Rebuild a collector via a temporary histogram-backed merge.
        foreach ($data['commands'] as $name => $cmd) {
            $histogram = new HdrHistogram(1, 600_000_000, 3);
            foreach ($cmd['counts'] as $index => $count) {
                $histogram->recordValueWithCount($histogram->valueFromIndex((int) $index), (int) $count);
            }
            $partial->ingestCommand($name, $cmd['requests'], $cmd['errors'], $histogram);
        }
        $partial->ingestTotals($data['total_requests'], $data['total_errors']);

        $collector->mergeFrom($partial);
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
}
