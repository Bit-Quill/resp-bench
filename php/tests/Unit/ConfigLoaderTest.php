<?php

declare(strict_types=1);

namespace RespBench\Tests\Unit;

use PHPUnit\Framework\TestCase;
use RespBench\Config\Loader;

final class ConfigLoaderTest extends TestCase
{
    public function testParsesDriverConfig(): void
    {
        $json = '{"schema_version":"1.0","driver_id":"valkey-glide-php","mode":"standalone","specific_driver_config":{"pool_size":8}}';
        $config = Loader::parseDriverConfigString($json);

        self::assertSame('valkey-glide-php', $config->driverId);
        self::assertSame('standalone', $config->mode);
        self::assertTrue($config->isStandalone());
        self::assertSame(8, $config->specificDriverConfig['pool_size']);
    }

    public function testDriverConfigDefaults(): void
    {
        $config = Loader::parseDriverConfigString('{}');
        self::assertSame('1.0', $config->schemaVersion);
        self::assertSame('standalone', $config->mode);
        self::assertNull($config->driverId);
        self::assertSame([], $config->specificDriverConfig);
    }

    public function testClusterMode(): void
    {
        $config = Loader::parseDriverConfigString('{"driver_id":"x","mode":"cluster"}');
        self::assertTrue($config->isCluster());
        self::assertFalse($config->isStandalone());
    }

    public function testSecondaryDriverId(): void
    {
        $json = '{"driver_id":"spring","specific_driver_config":{"secondary_driver_id":"valkey-glide"}}';
        $config = Loader::parseDriverConfigString($json);
        self::assertSame('valkey-glide', $config->secondaryDriverId());
    }

    public function testParsesWorkloadConfig(): void
    {
        $json = <<<JSON
        {
            "schema_version": "1.0",
            "benchmark_profile": {"name": "Test", "description": "desc"},
            "phases": [
                {
                    "id": "STEADY",
                    "connections": 16,
                    "rps_limit": 5000,
                    "completion": {"type": "requests", "requests": 100000},
                    "keyspace": {"keys_count": 1000, "key_prefix": "k:", "generation_alg": "uniform_rand", "seed": 7},
                    "commands": [
                        {"command": "GET", "weight": 0.7},
                        {"command": "set", "weight": 0.3, "data_size_bytes": 128}
                    ]
                }
            ]
        }
        JSON;

        $config = Loader::parseWorkloadConfigString($json);

        self::assertSame('Test', $config->name());
        self::assertCount(1, $config->phases);

        $phase = $config->phases[0];
        self::assertSame('STEADY', $phase->id);
        self::assertSame(16, $phase->connections);
        self::assertTrue($phase->hasRpsLimit());
        self::assertSame(5000, $phase->rpsLimit);
        self::assertTrue($phase->completion->isRequestBased());
        self::assertSame(100000, $phase->completion->totalRequests());

        self::assertSame('uniform_rand', $phase->keyspace->generationAlg);
        self::assertSame(7, $phase->keyspace->seed);
        self::assertSame('k:', $phase->keyspace->keyPrefix);

        self::assertCount(2, $phase->commands);
        // Command names are lowercased at config level.
        self::assertSame('get', $phase->commands[0]->command);
        self::assertEqualsWithDelta(0.7, $phase->commands[0]->weight, 1e-9);
        self::assertSame(128, $phase->commands[1]->dataSizeBytes);
    }

    public function testWorkloadDefaults(): void
    {
        $json = '{"phases":[{"id":"P","connections":1,"completion":{"type":"requests","requests":1},"keyspace":{"keys_count":1},"commands":[{"command":"ping","weight":1.0}]}]}';
        $config = Loader::parseWorkloadConfigString($json);
        $phase = $config->phases[0];

        self::assertSame(-1, $phase->cpsLimit);
        self::assertSame(-1, $phase->rpsLimit);
        self::assertFalse($phase->hasRpsLimit());
        self::assertSame(1, $phase->effectivePipelineDepth());
        // keyspace defaults
        self::assertSame('bench:', $phase->keyspace->keyPrefix);
        self::assertSame(16, $phase->keyspace->keySizeBytes);
        self::assertTrue($phase->keyspace->isSequentialInt());
    }
}
