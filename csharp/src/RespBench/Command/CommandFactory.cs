/*
 * Copyright 2025 the original author or authors.
 */
using RespBench.Command.Impl;
using RespBench.Config;

namespace RespBench.Command;

/// <summary>
/// Factory for creating Command instances.
/// </summary>
public static class CommandFactory
{
    private static readonly Dictionary<string, CommandInfo> CommandRegistry = new(StringComparer.OrdinalIgnoreCase);

    static CommandFactory()
    {
        RegisterCommand("set", "SET key value", () => new SetCommand());
        RegisterCommand("get", "GET key", () => new GetCommand());
        RegisterCommand("ping", "PING [message]", () => new PingCommand());
    }

    public static void RegisterCommand(string commandName, string description, Func<ICommand> factory)
    {
        CommandRegistry[commandName.ToLowerInvariant()] = new CommandInfo(commandName, description, factory);
    }

    public static bool IsSupported(string commandName) =>
        CommandRegistry.ContainsKey(commandName.ToLowerInvariant());

    public static ICommand Create(CommandConfig config)
    {
        string commandName = config.Command.ToLowerInvariant();
        if (!CommandRegistry.TryGetValue(commandName, out var info))
            throw new ArgumentException(
                $"Unsupported command: {config.Command}. Supported: {string.Join(", ", CommandRegistry.Keys)}");

        var command = info.Factory();
        command.Configure(config);
        return command;
    }

    public static List<ICommand> CreateAll(List<CommandConfig> configs) =>
        configs.Select(Create).ToList();

    public static IReadOnlyCollection<string> GetRegisteredCommandNames() => CommandRegistry.Keys;

    public static IReadOnlyList<CommandInfo> GetRegisteredCommands() => CommandRegistry.Values.ToList();

    public record CommandInfo(string Name, string Description, Func<ICommand> Factory);
}
