<?php

declare(strict_types=1);

namespace RespBench\Config;

/**
 * Configuration for a benchmark phase.
 */
final class PhaseConfig
{
    public const DEFAULT_PIPELINE_DEPTH = 1;
    public const DEFAULT_WARMUP_REQUESTS = 1;

    /**
     * @param list<CommandConfig> $commands
     */
    public function __construct(
        public readonly string $id,
        public readonly int $connections,
        public readonly CompletionConfig $completion,
        public readonly KeyspaceConfig $keyspace,
        public readonly array $commands,
        public readonly ?string $description = null,
        public readonly int $cpsLimit = -1,
        public readonly int $rpsLimit = -1,
        public readonly int $pipelineDepth = self::DEFAULT_PIPELINE_DEPTH,
        public readonly int $warmupRequests = self::DEFAULT_WARMUP_REQUESTS,
    ) {
    }

    public function hasCpsLimit(): bool
    {
        return $this->cpsLimit > 0;
    }

    public function hasRpsLimit(): bool
    {
        return $this->rpsLimit > 0;
    }

    public function effectivePipelineDepth(): int
    {
        return $this->pipelineDepth > 0 ? $this->pipelineDepth : self::DEFAULT_PIPELINE_DEPTH;
    }
}
