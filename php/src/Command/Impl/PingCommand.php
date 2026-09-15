<?php

declare(strict_types=1);

namespace RespBench\Command\Impl;

use RespBench\Client\BenchmarkClient;
use RespBench\Command\Command;
use RespBench\Command\CommandResult;
use RespBench\Engine\KeyGenerator;

final class PingCommand extends Command
{
    public function execute(BenchmarkClient $client, KeyGenerator $keyGenerator): CommandResult
    {
        $result = $client->ping();

        return new CommandResult($this->name, $result->latencyMicros, $result->isSuccess());
    }
}
