<?php

declare(strict_types=1);

namespace RespBench\Command;

/**
 * Result of a command execution, used for metrics collection.
 */
final class CommandResult
{
    public function __construct(
        public readonly string $commandName,
        public readonly int $latencyMicros,
        public readonly bool $success,
    ) {
    }
}
