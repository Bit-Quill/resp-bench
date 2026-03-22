/*
 * Copyright 2025 the original author or authors.
 */
using RespBench.Client;
using RespBench.Config;
using RespBench.Engine;

namespace RespBench.Command.Impl;

public class SetCommand : ICommand
{
    private double _weight = 1.0;
    private byte[] _valueBuffer = Array.Empty<byte>();

    public string Name => "SET";
    public string Description => "SET key value";
    public double Weight => _weight;

    public void Configure(CommandConfig config)
    {
        _weight = config.Weight;
        int dataSize = config.GetDataSizeBytesOrDefault(256);
        _valueBuffer = new byte[dataSize];
        Random.Shared.NextBytes(_valueBuffer);
    }

    public async Task<CommandResult> Execute(IBenchmarkClient client, KeyGenerator keyGenerator)
    {
        byte[] key = keyGenerator.NextKey();
        try
        {
            var result = await client.Set(key, _valueBuffer).ConfigureAwait(false);
            return CommandResult.SuccessResult("SET", result.LatencyMicros);
        }
        catch (Exception e)
        {
            return CommandResult.Failure("SET", 0, e.Message);
        }
    }
}
