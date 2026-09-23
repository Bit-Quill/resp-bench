<?php

declare(strict_types=1);

namespace RespBench\Command;

use InvalidArgumentException;
use RespBench\Command\Impl\GetCommand;
use RespBench\Command\Impl\PingCommand;
use RespBench\Command\Impl\SetCommand;
use RespBench\Config\CommandConfig;

/**
 * Factory for creating command instances.
 */
final class Factory
{
    /** @var array<string,class-string<Command>> */
    private const COMMAND_CLASSES = [
        'ping' => PingCommand::class,
        'get' => GetCommand::class,
        'set' => SetCommand::class,
    ];

    public static function create(CommandConfig $config): Command
    {
        $class = self::COMMAND_CLASSES[$config->command] ?? null;
        if ($class === null) {
            throw new InvalidArgumentException(
                "Unknown command: {$config->command}. Supported: "
                . implode(', ', array_keys(self::COMMAND_CLASSES))
            );
        }

        return new $class($config);
    }

    /**
     * @param list<CommandConfig> $configs
     * @return list<Command>
     */
    public static function createAll(array $configs): array
    {
        return array_map(static fn (CommandConfig $c): Command => self::create($c), $configs);
    }

    /**
     * @return list<string>
     */
    public static function supportedCommands(): array
    {
        return array_keys(self::COMMAND_CLASSES);
    }
}
