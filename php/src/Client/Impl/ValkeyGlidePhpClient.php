<?php

declare(strict_types=1);

namespace RespBench\Client\Impl;

use RespBench\Client\BenchmarkClient;
use RespBench\Client\TimedResult;
use RespBench\Config\DriverConfig;
use RuntimeException;

/**
 * Benchmark client backed by the Valkey GLIDE PHP extension (ext-valkey_glide).
 *
 * Uses the extension's ValkeyGlide / ValkeyGlideCluster classes. The API follows
 * the client's README:
 *
 *   $client = new ValkeyGlide();
 *   $client->connect(addresses: [['host' => 'localhost', 'port' => 6379]]);
 *   $client->set('foo', 'bar'); $client->get('foo'); $client->ping();
 *   $client->close();
 *
 * IMPORTANT: In the multi-process engine, each worker constructs and connects its
 * own client AFTER forking — a connection is never inherited across a fork.
 */
final class ValkeyGlidePhpClient extends BenchmarkClient
{
    private ?object $client = null;

    public function connect(string $host, int $port, DriverConfig $config): void
    {
        if (!extension_loaded('valkey_glide')) {
            throw new RuntimeException(
                'The valkey_glide PHP extension is not loaded. '
                . 'Install it (see php/README.md) and add extension=valkey_glide to php.ini.'
            );
        }

        $addresses = [['host' => $host, 'port' => $port]];
        $useTls = false;
        $advancedConfig = null;
        $credentials = null;

        if ($config->tls !== null) {
            $useTls = true;
            $tlsConfig = [];
            if (isset($config->tls['ca_cert_path'])) {
                $tlsConfig['root_certs'] = (string) file_get_contents((string) $config->tls['ca_cert_path']);
            }
            if (isset($config->tls['cert_path'])) {
                $tlsConfig['client_cert'] = (string) file_get_contents((string) $config->tls['cert_path']);
            }
            if (isset($config->tls['key_path'])) {
                $tlsConfig['client_key'] = (string) file_get_contents((string) $config->tls['key_path']);
            }
            if ($tlsConfig !== []) {
                $advancedConfig = ['tls_config' => $tlsConfig];
            }
        }

        if ($config->auth !== null) {
            $credentials = [];
            if (isset($config->auth['username'])) {
                $credentials['username'] = (string) $config->auth['username'];
            }
            if (isset($config->auth['password'])) {
                $credentials['password'] = (string) $config->auth['password'];
            }
        }

        $this->client = $config->isCluster()
            ? $this->makeClient('ValkeyGlideCluster', $addresses, $useTls, $advancedConfig, $credentials)
            : $this->makeClient('ValkeyGlide', $addresses, $useTls, $advancedConfig, $credentials);
    }

    /**
     * @param list<array{host:string,port:int}> $addresses
     * @param array<string,mixed>|null $advancedConfig
     * @param array<string,mixed>|null $credentials
     */
    private function makeClient(
        string $class,
        array $addresses,
        bool $useTls,
        ?array $advancedConfig,
        ?array $credentials,
    ): object {
        if (!class_exists($class)) {
            throw new RuntimeException("{$class} class not available from the valkey_glide extension.");
        }

        // ValkeyGlideCluster connects via constructor; ValkeyGlide via connect().
        if ($class === 'ValkeyGlideCluster') {
            /** @psalm-suppress MixedMethodCall */
            return new $class(
                addresses: $addresses,
                use_tls: $useTls,
                credentials: $credentials,
                advanced_config: $advancedConfig,
            );
        }

        /** @psalm-suppress MixedMethodCall */
        $client = new $class();
        $client->connect(
            addresses: $addresses,
            use_tls: $useTls,
            credentials: $credentials,
            advanced_config: $advancedConfig,
        );

        return $client;
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
                // ignore close errors
            }
            $this->client = null;
        }
    }

    public function driverVersion(): string
    {
        $version = phpversion('valkey_glide');

        return $version !== false ? $version : 'unknown';
    }

    private function requireClient(): object
    {
        if ($this->client === null) {
            throw new RuntimeException('ValkeyGlide client is not connected.');
        }

        return $this->client;
    }
}
