<?php

declare(strict_types=1);

namespace RespBench\Command;

use RespBench\Client\BenchmarkClient;
use RespBench\Config\CommandConfig;
use RespBench\Engine\KeyGenerator;

/**
 * Abstract base class for benchmark commands.
 */
abstract class Command
{
    public readonly float $weight;
    public readonly string $name;
    protected readonly int $dataSizeBytes;

    public function __construct(CommandConfig $config)
    {
        $this->weight = $config->weight;
        $this->name = strtoupper($config->command);
        $this->dataSizeBytes = $config->dataSizeBytes;
    }

    /**
     * Execute the command and return a result for metrics.
     */
    abstract public function execute(BenchmarkClient $client, KeyGenerator $keyGenerator): CommandResult;
}
