/*
 * Copyright 2025 the original author or authors.
 */
using RespBench.Client;
using RespBench.Config;
using RespBench.Engine;

namespace RespBench.Command;

/// <summary>
/// Interface for benchmark commands.
/// </summary>
public interface ICommand
{
    string Name { get; }
    string Description { get; }
    void Configure(CommandConfig config);
    Task<CommandResult> Execute(IBenchmarkClient client, KeyGenerator keyGenerator);
    double Weight { get; }
}

/// <summary>
/// Command result containing timing and status.
/// </summary>
public record CommandResult(string CommandName, bool Success, long LatencyMicros, string? ErrorMessage)
{
    public static CommandResult SuccessResult(string commandName, long latencyMicros) =>
        new(commandName, true, latencyMicros, null);

    public static CommandResult Failure(string commandName, long latencyMicros, string errorMessage) =>
        new(commandName, false, latencyMicros, errorMessage);

    public static CommandResult Failure(string commandName, string errorMessage) =>
        new(commandName, false, 0, errorMessage);
}
