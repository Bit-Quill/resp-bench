<?php

declare(strict_types=1);

namespace RespBench\Config;

use JsonException;
use RuntimeException;

/**
 * Loads driver and workload configuration from JSON files or strings.
 */
final class Loader
{
    public static function loadDriverConfig(string $path): DriverConfig
    {
        return self::parseDriverConfig(self::parseJsonFile($path));
    }

    public static function loadWorkloadConfig(string $path): WorkloadConfig
    {
        return self::parseWorkloadConfig(self::parseJsonFile($path));
    }

    public static function parseDriverConfigString(string $json): DriverConfig
    {
        return self::parseDriverConfig(self::decode($json));
    }

    public static function parseWorkloadConfigString(string $json): WorkloadConfig
    {
        return self::parseWorkloadConfig(self::decode($json));
    }

    /**
     * @param array<string,mixed> $json
     */
    public static function parseDriverConfig(array $json): DriverConfig
    {
        return new DriverConfig(
            schemaVersion: (string) ($json['schema_version'] ?? '1.0'),
            description: isset($json['description']) ? (string) $json['description'] : null,
            driverId: isset($json['driver_id']) ? (string) $json['driver_id'] : null,
            mode: (string) ($json['mode'] ?? 'standalone'),
            tls: isset($json['tls']) && is_array($json['tls']) ? $json['tls'] : null,
            auth: isset($json['auth']) && is_array($json['auth']) ? $json['auth'] : null,
            specificDriverConfig: isset($json['specific_driver_config']) && is_array($json['specific_driver_config'])
                ? $json['specific_driver_config']
                : [],
        );
    }

    /**
     * @param array<string,mixed> $json
     */
    public static function parseWorkloadConfig(array $json): WorkloadConfig
    {
        $phases = [];
        foreach (($json['phases'] ?? []) as $phase) {
            $phases[] = self::parsePhaseConfig($phase);
        }

        return new WorkloadConfig(
            schemaVersion: (string) ($json['schema_version'] ?? '1.0'),
            benchmarkProfile: isset($json['benchmark_profile']) && is_array($json['benchmark_profile'])
                ? $json['benchmark_profile']
                : [],
            phases: $phases,
        );
    }

    /**
     * @param array<string,mixed> $json
     */
    private static function parsePhaseConfig(array $json): PhaseConfig
    {
        $commands = [];
        foreach (($json['commands'] ?? []) as $command) {
            $commands[] = self::parseCommandConfig($command);
        }

        return new PhaseConfig(
            id: (string) $json['id'],
            connections: (int) $json['connections'],
            completion: self::parseCompletionConfig($json['completion'] ?? []),
            keyspace: self::parseKeyspaceConfig($json['keyspace'] ?? []),
            commands: $commands,
            description: isset($json['description']) ? (string) $json['description'] : null,
            cpsLimit: isset($json['cps_limit']) ? (int) $json['cps_limit'] : -1,
            rpsLimit: isset($json['rps_limit']) ? (int) $json['rps_limit'] : -1,
            pipelineDepth: isset($json['pipeline_depth'])
                ? (int) $json['pipeline_depth']
                : PhaseConfig::DEFAULT_PIPELINE_DEPTH,
            warmupRequests: isset($json['warmup_requests'])
                ? (int) $json['warmup_requests']
                : PhaseConfig::DEFAULT_WARMUP_REQUESTS,
        );
    }

    /**
     * @param array<string,mixed> $json
     */
    private static function parseCompletionConfig(array $json): CompletionConfig
    {
        return new CompletionConfig(
            type: (string) ($json['type'] ?? 'requests'),
            seconds: isset($json['seconds']) ? (int) $json['seconds'] : null,
            requests: isset($json['requests']) ? (int) $json['requests'] : null,
        );
    }

    /**
     * @param array<string,mixed> $json
     */
    private static function parseKeyspaceConfig(array $json): KeyspaceConfig
    {
        return new KeyspaceConfig(
            keysCount: (int) ($json['keys_count'] ?? 1),
            keySizeBytes: isset($json['key_size_bytes'])
                ? (int) $json['key_size_bytes']
                : KeyspaceConfig::DEFAULT_KEY_SIZE_BYTES,
            keyPrefix: isset($json['key_prefix'])
                ? (string) $json['key_prefix']
                : KeyspaceConfig::DEFAULT_KEY_PREFIX,
            generationAlg: (string) ($json['generation_alg'] ?? 'sequential_int'),
            seed: isset($json['seed']) ? (int) $json['seed'] : null,
        );
    }

    /**
     * @param array<string,mixed> $json
     */
    private static function parseCommandConfig(array $json): CommandConfig
    {
        return new CommandConfig(
            command: (string) $json['command'],
            weight: (float) $json['weight'],
            dataSizeBytes: isset($json['data_size_bytes'])
                ? (int) $json['data_size_bytes']
                : CommandConfig::DEFAULT_DATA_SIZE_BYTES,
        );
    }

    /**
     * @return array<string,mixed>
     */
    private static function parseJsonFile(string $path): array
    {
        $content = @file_get_contents($path);
        if ($content === false) {
            throw new RuntimeException("Cannot read config file: {$path}");
        }

        return self::decode($content);
    }

    /**
     * @return array<string,mixed>
     */
    private static function decode(string $json): array
    {
        try {
            /** @var array<string,mixed> $decoded */
            $decoded = json_decode($json, true, 512, JSON_THROW_ON_ERROR);
        } catch (JsonException $e) {
            throw new RuntimeException('Invalid JSON config: ' . $e->getMessage(), 0, $e);
        }

        return $decoded;
    }
}
