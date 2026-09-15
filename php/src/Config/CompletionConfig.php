<?php

declare(strict_types=1);

namespace RespBench\Config;

/**
 * Configuration for phase completion criteria.
 */
final class CompletionConfig
{
    public function __construct(
        public readonly string $type,
        public readonly ?int $seconds = null,
        public readonly ?int $requests = null,
    ) {
    }

    public function isDurationBased(): bool
    {
        return $this->type === 'duration';
    }

    public function isRequestBased(): bool
    {
        return $this->type === 'requests';
    }

    public function durationSeconds(): int
    {
        return $this->seconds ?? 0;
    }

    public function totalRequests(): int
    {
        return $this->requests ?? 0;
    }
}
