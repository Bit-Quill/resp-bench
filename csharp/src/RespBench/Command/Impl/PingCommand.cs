/*
 * Copyright 2025 the original author or authors.
 */
using RespBench.Client;
using RespBench.Config;
using RespBench.Engine;

namespace RespBench.Command.Impl;

public class PingCommand : ICommand
{
    private double _weight = 1.0;

    public string Name => "PING";
    public string Description => "PING [message]";
    public double Weight => _weight;

    public void Configure(CommandConfig config)
    {
        _weight = config.Weight;
    }

    public async Task<CommandResult> Execute(IBenchmarkClient client, KeyGenerator keyGenerator)
    {
        try
        {
            var result = await client.Ping().ConfigureAwait(false);
            return CommandResult.SuccessResult("PING", result.LatencyMicros);
        }
        catch (Exception e)
        {
            return CommandResult.Failure("PING", 0, e.Message);
        }
    }
}
