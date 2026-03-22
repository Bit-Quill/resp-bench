/*
 * Copyright 2025 the original author or authors.
 */
using System.Text;
using RespBench.Config;

namespace RespBench.Engine;

/// <summary>
/// Key generator for benchmark operations.
/// </summary>
public class KeyGenerator
{
    private readonly KeyspaceConfig _config;
    private long _sequentialCounter;
    private readonly Random _random;
    private readonly string _keyPrefix;
    private readonly int _keySizeBytes;
    private readonly int _keysCount;
    private readonly string _formatPattern;

    public KeyGenerator(KeyspaceConfig config) : this(config, 0) { }

    /// <summary>
    /// Creates a KeyGenerator with a thread-specific seed offset.
    /// For uniform_rand, the seed is offset by threadIndex to ensure each thread
    /// generates a different random sequence. For sequential_int, the counter is
    /// shared via Interlocked so threads naturally interleave.
    /// </summary>
    public KeyGenerator(KeyspaceConfig config, int threadIndex)
    {
        _config = config;
        _keyPrefix = config.EffectiveKeyPrefix;
        _keySizeBytes = config.KeySizeBytes;
        _keysCount = config.KeysCount;
        _sequentialCounter = 0;
        _random = new Random((int)(config.SeedValue + threadIndex));

        int numDigits = Math.Max(1, _keySizeBytes - _keyPrefix.Length);
        _formatPattern = "D" + numDigits;
    }

    /// <summary>
    /// Internal constructor for forking with a shared sequential counter reference.
    /// Note: In C# we use Interlocked for thread-safe counter access.
    /// </summary>
    private KeyGenerator(KeyspaceConfig config, KeyGenerator parent, int threadIndex)
    {
        _config = config;
        _keyPrefix = config.EffectiveKeyPrefix;
        _keySizeBytes = config.KeySizeBytes;
        _keysCount = config.KeysCount;
        // For forked generators, we track the parent for shared counter
        _parentGenerator = parent;
        _sequentialCounter = 0; // Not used directly for forked generators
        _random = new Random((int)(config.SeedValue + threadIndex));

        int numDigits = Math.Max(1, _keySizeBytes - _keyPrefix.Length);
        _formatPattern = "D" + numDigits;
    }

    private readonly KeyGenerator? _parentGenerator;

    /// <summary>
    /// Creates a KeyGenerator that shares the same sequential counter as this one,
    /// but with an independent Random instance seeded differently.
    /// </summary>
    public KeyGenerator ForkForThread(int threadIndex)
    {
        return new KeyGenerator(_config, this, threadIndex);
    }

    public byte[] NextKey()
    {
        long keyIndex;
        if (_config.IsSequentialInt)
        {
            // Use thread-safe increment on parent or self
            var target = _parentGenerator ?? this;
            keyIndex = Interlocked.Increment(ref target._sequentialCounter) - 1;
            keyIndex %= _keysCount;
        }
        else
        {
            keyIndex = _random.Next(_keysCount);
        }

        string keyStr = _keyPrefix + keyIndex.ToString(_formatPattern);
        return Encoding.UTF8.GetBytes(keyStr);
    }

    public void Reset()
    {
        Interlocked.Exchange(ref _sequentialCounter, 0);
    }

    public static KeyGenerator Create(KeyspaceConfig config) => new(config);
}
