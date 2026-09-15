<?php

declare(strict_types=1);

namespace RespBench\Client\Impl;

use RespBench\Client\BenchmarkClient;
use RespBench\Client\TimedResult;
use RespBench\Config\DriverConfig;
use RuntimeException;

/**
 * Benchmark client backed by the PHPRedis extension (ext-redis) — the de-facto
 * standard PHP Redis/Valkey client, and the API the GLIDE PHP client is a
 * drop-in replacement for. This is PHP's incumbent-comparison driver, analogous
 * to redis-rb (Ruby) and StackExchange.Redis (C#).
 *
 * API: https://github.com/phpredis/phpredis
 *   $r = new Redis();
 *   $r->connect($host, $port);
 *   $r->set('k','v'); $r->get('k'); $r->ping(); $r->del('k'); $r->close();
 *
 * As with the GLIDE client, each forked worker constructs and connects its own
 * instance AFTER forking — connections are never inherited across a fork.
 */
final class PhpRedisClient extends BenchmarkClient
{
    private ?object $client = null;

    public function connect(string $host, int $port, DriverConfig $config): void
    {
        if (!extension_loaded('redis')) {
            throw new RuntimeException(
                'The PHPRedis extension (ext-redis) is not loaded. '
                . 'Install it (pecl install redis) and add extension=redis to php.ini.'
            );
        }

        if ($config->isCluster()) {
            $this->client = $this->makeCluster($host, $port, $config);

            return;
        }

        /** @var object $redis */
        $redis = new \Redis();

        // Optional TLS: PHPRedis uses a tls:// host scheme.
        $connectHost = $host;
        $sslContext = null;
        if ($config->tls !== null) {
            $connectHost = 'tls://' . $host;
            $sslContext = $this->buildSslContext($config->tls);
        }

        // connect(host, port, timeout, persistent_id, retry_interval, read_timeout, context)
        $timeoutSeconds = 0.5;
        $ok = $sslContext !== null
            ? $redis->connect($connectHost, $port, $timeoutSeconds, null, 0, 0, ['stream' => $sslContext])
            : $redis->connect($connectHost, $port, $timeoutSeconds);

        if ($ok === false) {
            throw new RuntimeException("PHPRedis failed to connect to {$host}:{$port}");
        }

        if ($config->auth !== null && isset($config->auth['password'])) {
            $auth = isset($config->auth['username'])
                ? [(string) $config->auth['username'], (string) $config->auth['password']]
                : (string) $config->auth['password'];
            $redis->auth($auth);
        }

        $this->client = $redis;
    }

    private function makeCluster(string $host, int $port, DriverConfig $config): object
    {
        if (!class_exists('RedisCluster')) {
            throw new RuntimeException('RedisCluster not available from ext-redis.');
        }

        // RedisCluster(name, seeds, timeout, read_timeout, persistent, auth)
        $seeds = ["{$host}:{$port}"];
        $auth = null;
        if ($config->auth !== null && isset($config->auth['password'])) {
            $auth = (string) $config->auth['password'];
        }

        /** @psalm-suppress MixedMethodCall */
        return new \RedisCluster(null, $seeds, 0.5, 0.5, false, $auth);
    }

    /**
     * @param array<string,mixed> $tls
     * @return array<string,mixed>
     */
    private function buildSslContext(array $tls): array
    {
        $ssl = [];
        if (isset($tls['ca_cert_path'])) {
            $ssl['cafile'] = (string) $tls['ca_cert_path'];
        }
        if (isset($tls['cert_path'])) {
            $ssl['local_cert'] = (string) $tls['cert_path'];
        }
        if (isset($tls['key_path'])) {
            $ssl['local_pk'] = (string) $tls['key_path'];
        }
        if (isset($tls['verify_peer'])) {
            $ssl['verify_peer'] = (bool) $tls['verify_peer'];
        }

        return $ssl;
    }

    public function isConnected(): bool
    {
        if ($this->client === null) {
            return false;
        }

        try {
            return $this->client->ping() !== false;
        } catch (\Throwable) {
            return false;
        }
    }

    public function ping(): TimedResult
    {
        return $this->measure(fn (): mixed => $this->requireClient()->ping());
    }

    public function get(string $key): TimedResult
    {
        return $this->measure(fn (): mixed => $this->requireClient()->get($key));
    }

    public function set(string $key, string $value): TimedResult
    {
        return $this->measure(fn (): mixed => $this->requireClient()->set($key, $value));
    }

    public function del(string $key): TimedResult
    {
        return $this->measure(fn (): mixed => $this->requireClient()->del($key));
    }

    public function close(): void
    {
        if ($this->client !== null) {
            try {
                $this->client->close();
            } catch (\Throwable) {
                // ignore
            }
            $this->client = null;
        }
    }

    public function driverVersion(): string
    {
        $version = phpversion('redis');

        return $version !== false ? $version : 'unknown';
    }

    private function requireClient(): object
    {
        if ($this->client === null) {
            throw new RuntimeException('PHPRedis client is not connected.');
        }

        return $this->client;
    }
}
