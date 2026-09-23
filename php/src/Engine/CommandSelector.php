<?php

declare(strict_types=1);

namespace RespBench\Engine;

use RespBench\Command\Command;

/**
 * Selects commands based on their configured weights, using cumulative weights.
 */
final class CommandSelector
{
    /** @var list<Command> */
    private readonly array $commands;

    /** @var list<float> */
    private readonly array $cumulativeWeights;

    /**
     * @param list<Command> $commands
     */
    public function __construct(array $commands)
    {
        $this->commands = $commands;
        $this->cumulativeWeights = self::buildCumulativeWeights($commands);
    }

    public function select(): Command
    {
        // mt_rand()/mt_getrandmax() gives a float in [0, 1].
        $r = mt_rand() / mt_getrandmax();
        foreach ($this->cumulativeWeights as $index => $threshold) {
            if ($r <= $threshold) {
                return $this->commands[$index];
            }
        }

        return $this->commands[array_key_last($this->commands)];
    }

    /**
     * @param list<Command> $commands
     * @return list<float>
     */
    private static function buildCumulativeWeights(array $commands): array
    {
        $totalWeight = 0.0;
        foreach ($commands as $cmd) {
            $totalWeight += $cmd->weight;
        }
        if ($totalWeight === 0.0) {
            $totalWeight = 1.0;
        }

        $cumulative = [];
        $sum = 0.0;
        foreach ($commands as $cmd) {
            $sum += $cmd->weight / $totalWeight;
            $cumulative[] = $sum;
        }

        return $cumulative;
    }
}
