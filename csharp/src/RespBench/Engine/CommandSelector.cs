/*
 * Copyright 2025 the original author or authors.
 */
using RespBench.Command;

namespace RespBench.Engine;

/// <summary>
/// Selects commands based on their weights.
/// </summary>
public class CommandSelector
{
    private readonly List<ICommand> _commands;
    private readonly double[] _cumulativeWeights;
    private readonly Random _random = new();

    public CommandSelector(List<ICommand> commands)
    {
        _commands = commands;
        _cumulativeWeights = new double[commands.Count];

        double sum = 0;
        for (int i = 0; i < commands.Count; i++)
        {
            sum += commands[i].Weight;
            _cumulativeWeights[i] = sum;
        }
    }

    public ICommand Select()
    {
        double r = _random.NextDouble();
        for (int i = 0; i < _cumulativeWeights.Length; i++)
        {
            if (r <= _cumulativeWeights[i])
            {
                return _commands[i];
            }
        }
        return _commands[^1];
    }
}
