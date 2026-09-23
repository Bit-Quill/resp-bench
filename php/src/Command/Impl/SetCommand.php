<?php

declare(strict_types=1);

namespace RespBench\Command\Impl;

use RespBench\Client\BenchmarkClient;
use RespBench\Command\Command;
use RespBench\Command\CommandResult;
use RespBench\Config\CommandConfig;
use RespBench\Engine\KeyGenerator;

final class SetCommand extends Command
{
    private readonly string $value;

    public function __construct(CommandConfig $config)
    {
        parent::__construct($config);
        $this->value = self::generateValue($this->dataSizeBytes);
    }

    public function execute(BenchmarkClient $client, KeyGenerator $keyGenerator): CommandResult
    {
        $key = $keyGenerator->nextKey();
        $result = $client->set($key, $this->value);

        return new CommandResult($this->name, $result->latencyMicros, $result->isSuccess());
    }

    /**
     * Deterministic value of the requested size (matches the Ruby engine pattern).
     */
    private static function generateValue(int $size): string
    {
        if ($size <= 0) {
            return '';
        }

        $pattern = '0123456789ABCDEF';
        $repeat = intdiv($size, strlen($pattern)) + 1;

        return substr(str_repeat($pattern, $repeat), 0, $size);
    }
}
