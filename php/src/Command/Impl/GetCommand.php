<?php

declare(strict_types=1);

namespace RespBench\Command\Impl;

use RespBench\Client\BenchmarkClient;
use RespBench\Command\Command;
use RespBench\Command\CommandResult;
use RespBench\Engine\KeyGenerator;

final class GetCommand extends Command
{
    public function execute(BenchmarkClient $client, KeyGenerator $keyGenerator): CommandResult
    {
        $key = $keyGenerator->nextKey();
        $result = $client->get($key);

        return new CommandResult($this->name, $result->latencyMicros, $result->isSuccess());
    }
}
