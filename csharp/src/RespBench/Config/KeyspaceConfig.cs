/*
 * Copyright 2025 the original author or authors.
 */
using System.Text.Json.Serialization;

namespace RespBench.Config;

/// <summary>
/// Configuration model for keyspace settings.
/// </summary>
public class KeyspaceConfig
{
    public const string AlgSequentialInt = "sequential_int";
    public const string AlgUniformRand = "uniform_rand";

    [JsonPropertyName("seed")]
    public long? Seed { get; set; }

    [JsonPropertyName("keys_count")]
    public int KeysCount { get; set; }

    [JsonPropertyName("key_size_bytes")]
    public int KeySizeBytes { get; set; } = 16;

    [JsonPropertyName("key_prefix")]
    public string KeyPrefix { get; set; } = "";

    [JsonPropertyName("generation_alg")]
    public string GenerationAlg { get; set; } = "";

    // Convenience methods

    /// <summary>Check if using sequential integer key generation.</summary>
    public bool IsSequentialInt => AlgSequentialInt.Equals(GenerationAlg, StringComparison.OrdinalIgnoreCase);

    /// <summary>Check if using uniform random key generation.</summary>
    public bool IsUniformRand => AlgUniformRand.Equals(GenerationAlg, StringComparison.OrdinalIgnoreCase);

    /// <summary>Get the seed value, defaulting to 0 if not set.</summary>
    public long SeedValue => Seed ?? 0L;

    /// <summary>Get the effective key prefix (never null).</summary>
    public string EffectiveKeyPrefix => KeyPrefix ?? "";

    /// <summary>Validate the keyspace configuration.</summary>
    public void Validate()
    {
        if (KeysCount <= 0)
            throw new ArgumentException("Keys count must be positive");
        if (KeySizeBytes <= 0)
            throw new ArgumentException("Key size bytes must be positive");
        if (string.IsNullOrWhiteSpace(GenerationAlg))
            throw new ArgumentException("Generation algorithm is required");
        if (!IsSequentialInt && !IsUniformRand)
            throw new ArgumentException(
                $"Unknown generation algorithm: {GenerationAlg}. Supported: {AlgSequentialInt}, {AlgUniformRand}");
    }

    public override string ToString() =>
        $"KeyspaceConfig{{keysCount={KeysCount}, keySizeBytes={KeySizeBytes}, keyPrefix='{KeyPrefix}', generationAlg='{GenerationAlg}'{(Seed.HasValue ? $", seed={Seed}" : "")}}}";
}
