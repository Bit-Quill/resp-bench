<?php

declare(strict_types=1);

namespace RespBench\Metrics;

use RespBench\Command\CommandResult;

/**
 * Per-command metrics: request/error counts and a latency histogram.
 */
final class CommandMetrics
{
    private int $requests = 0;
    private int $errors = 0;
    private ?HdrHistogram $histogram = null;

    public function __construct(public readonly string $commandName)
    {
    }

    public function record(CommandResult $result): void
    {
        $this->requests++;
        if ($result->success) {
            $latency = min($result->latencyMicros, 600_000_000);
            $this->histogram ??= new HdrHistogram(1, 600_000_000, 3);
            $this->histogram->record($latency);
        } else {
            $this->errors++;
        }
    }

    public function mergeFrom(self $other): void
    {
        $this->requests += $other->requests;
        $this->errors += $other->errors;
        if ($other->histogram !== null && $other->histogram->totalCount() > 0) {
            $this->histogram ??= new HdrHistogram(1, 600_000_000, 3);
            $this->histogram->merge($other->histogram);
        }
    }

    /**
     * Ingest raw counts plus a fully-built histogram (cross-process reconstruction).
     */
    public function ingest(int $requests, int $errors, HdrHistogram $histogram): void
    {
        $this->requests += $requests;
        $this->errors += $errors;
        if ($histogram->totalCount() > 0) {
            $this->histogram ??= new HdrHistogram(1, 600_000_000, 3);
            $this->histogram->merge($histogram);
        }
    }

    public function requests(): int
    {
        return $this->requests;
    }

    public function errors(): int
    {
        return $this->errors;
    }

    public function histogram(): ?HdrHistogram
    {
        return $this->histogram;
    }

    public function count(): int
    {
        return $this->histogram?->totalCount() ?? 0;
    }

    public function min(): int
    {
        return $this->histogram?->min() ?? 0;
    }

    public function max(): int
    {
        return $this->histogram?->max() ?? 0;
    }

    public function percentile(float $p): int
    {
        return $this->histogram?->valueAtPercentile($p) ?? 0;
    }
}

/**
 * Collects benchmark metrics. Used both inside a worker (record()) and in the
 * parent to merge partial collectors from all forked workers.
 */
final class Collector
{
    /** @var array<string,CommandMetrics> */
    private array $commandMetrics = [];
    private int $totalRequests = 0;
    private int $totalErrors = 0;
    private ?float $startTime = null;
    private ?float $endTime = null;

    public function start(): void
    {
        $this->startTime = microtime(true);
    }

    public function stop(): void
    {
        $this->endTime = microtime(true);
    }

    public function record(CommandResult $result): void
    {
        $this->totalRequests++;
        if (!$result->success) {
            $this->totalErrors++;
        }

        $metrics = $this->commandMetrics[$result->commandName]
            ??= new CommandMetrics($result->commandName);
        $metrics->record($result);
    }

    /**
     * Merge another collector's per-command metrics and totals into this one.
     */
    public function mergeFrom(self $other): void
    {
        $this->totalRequests += $other->totalRequests;
        $this->totalErrors += $other->totalErrors;

        foreach ($other->commandMetrics as $name => $metrics) {
            $merged = $this->commandMetrics[$name] ??= new CommandMetrics($name);
            $merged->mergeFrom($metrics);
        }
    }

    /**
     * Ingest a reconstructed command's counts + histogram (used when rebuilding a
     * partial collector from a forked worker's serialized payload).
     */
    public function ingestCommand(string $name, int $requests, int $errors, HdrHistogram $histogram): void
    {
        $metrics = $this->commandMetrics[$name] ??= new CommandMetrics($name);
        $metrics->ingest($requests, $errors, $histogram);
    }

    public function ingestTotals(int $requests, int $errors): void
    {
        $this->totalRequests += $requests;
        $this->totalErrors += $errors;
    }

    public function totalRequests(): int
    {
        return $this->totalRequests;
    }

    public function totalErrors(): int
    {
        return $this->totalErrors;
    }

    public function startTime(): ?float
    {
        return $this->startTime;
    }

    public function endTime(): ?float
    {
        return $this->endTime;
    }

    public function setStartTime(float $t): void
    {
        $this->startTime = $t;
    }

    public function setEndTime(float $t): void
    {
        $this->endTime = $t;
    }

    public function durationMillis(): int
    {
        if ($this->startTime === null || $this->endTime === null) {
            return 0;
        }

        return (int) (($this->endTime - $this->startTime) * 1000);
    }

    /**
     * @return array<string,CommandMetrics>
     */
    public function allMetrics(): array
    {
        return $this->commandMetrics;
    }
}
