<?php

declare(strict_types=1);

namespace RespBench\Client;

use InvalidArgumentException;
use RespBench\Client\Impl\PhpRedisClient;
use RespBench\Client\Impl\RecordingClient;
use RespBench\Client\Impl\ValkeyGlidePhpClient;
use RespBench\Config\DriverConfig;

/**
 * Factory for creating benchmark client instances by driver id.
 */
final class Factory
{
    /** @var array<string,class-string<BenchmarkClient>> */
    private const DRIVERS = [
        'valkey-glide-php' => ValkeyGlidePhpClient::class,
        'phpredis' => PhpRedisClient::class,
        'recording' => RecordingClient::class,
    ];

    public static function create(string $driverId): BenchmarkClient
    {
        $class = self::DRIVERS[$driverId] ?? null;
        if ($class === null) {
            throw new InvalidArgumentException(
                "Unknown driver: {$driverId}. Supported: " . implode(', ', array_keys(self::DRIVERS))
            );
        }

        return new $class();
    }

    public static function createAndConnect(string $host, int $port, DriverConfig $config): BenchmarkClient
    {
        $client = self::create((string) $config->driverId);
        $client->connect($host, $port, $config);

        return $client;
    }

    /**
     * @return list<string>
     */
    public static function supportedDrivers(): array
    {
        return array_keys(self::DRIVERS);
    }
}
