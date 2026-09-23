<?php

declare(strict_types=1);

namespace RespBench\Tests\Unit;

use PHPUnit\Framework\TestCase;
use RespBench\Command\Factory as CommandFactory;
use RespBench\Config\CommandConfig;
use RespBench\Engine\CommandSelector;

final class CommandSelectorTest extends TestCase
{
    public function testSingleCommandAlwaysSelected(): void
    {
        $commands = CommandFactory::createAll([new CommandConfig('get', 1.0)]);
        $selector = new CommandSelector($commands);

        for ($i = 0; $i < 50; $i++) {
            self::assertSame('GET', $selector->select()->name);
        }
    }

    public function testWeightedDistributionApproximatelyHolds(): void
    {
        $commands = CommandFactory::createAll([
            new CommandConfig('get', 0.8),
            new CommandConfig('set', 0.2, 64),
        ]);
        $selector = new CommandSelector($commands);

        $counts = ['GET' => 0, 'SET' => 0];
        $n = 20000;
        for ($i = 0; $i < $n; $i++) {
            $counts[$selector->select()->name]++;
        }

        $getRatio = $counts['GET'] / $n;
        // Expect ~0.8; allow +/- 0.05 for randomness.
        self::assertGreaterThan(0.72, $getRatio);
        self::assertLessThan(0.88, $getRatio);
    }
}
