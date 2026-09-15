<?php

declare(strict_types=1);

namespace RespBench\Tests\Unit;

use InvalidArgumentException;
use PHPUnit\Framework\TestCase;
use RespBench\Client\Factory;
use RespBench\Client\Impl\PhpRedisClient;
use RespBench\Client\Impl\RecordingClient;
use RespBench\Client\Impl\ValkeyGlidePhpClient;

final class FactoryTest extends TestCase
{
    public function testSupportedDriversIncludesBothRealClients(): void
    {
        $drivers = Factory::supportedDrivers();

        self::assertContains('valkey-glide-php', $drivers);
        self::assertContains('phpredis', $drivers);
        self::assertContains('recording', $drivers);
    }

    public function testCreatesValkeyGlideClient(): void
    {
        self::assertInstanceOf(ValkeyGlidePhpClient::class, Factory::create('valkey-glide-php'));
    }

    public function testCreatesPhpRedisClient(): void
    {
        self::assertInstanceOf(PhpRedisClient::class, Factory::create('phpredis'));
    }

    public function testCreatesRecordingClient(): void
    {
        self::assertInstanceOf(RecordingClient::class, Factory::create('recording'));
    }

    public function testUnknownDriverThrows(): void
    {
        $this->expectException(InvalidArgumentException::class);
        Factory::create('nonexistent-driver');
    }
}
