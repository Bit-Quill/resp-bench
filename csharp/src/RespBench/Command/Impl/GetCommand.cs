/*
 * Copyright 2025 the original author or authors.
 */
using RespBench.Client;
using RespBench.Config;
using RespBench.Engine;

namespace RespBench.Command.Impl;

public class GetCommand : ICommand
{
    private double _weight = 1.0;

    public string Name => "GET";
    public string Description => "GET key";
    public double Weight => _weight;

    public void Configure(CommandConfig config)
    {
        _weight = config.Weight;
    }

    public async Task<CommandResult> Execute(IBenchmarkClient client, KeyGenerator keyGenerator)
    {
        byte[] key = keyGenerator.NextKey();
        try
        {
            var result = await client.Get(key).ConfigureAwait(false);
            return CommandResult.SuccessResult("GET", result.LatencyMicros);
        }
        catch (Exception e)
        {
            return CommandResult.Failure("GET", 0, e.Message);
        }
    }
}
