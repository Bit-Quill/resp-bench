/*
 * Copyright 2025 the original author or authors.
 */
using System.Text.Json.Serialization;

namespace RespBench.Config;

/// <summary>
/// Configuration model for a benchmark command.
/// </summary>
public class CommandConfig
{
    [JsonPropertyName("command")]
    public string Command { get; set; } = "";

    [JsonPropertyName("weight")]
    public double Weight { get; set; } = 1.0;

    [JsonPropertyName("data_size_bytes")]
    public int? DataSizeBytes { get; set; }

    [JsonPropertyName("settings")]
    public Dictionary<string, object>? Settings { get; set; }

    // Convenience methods

    /// <summary>Get the command name in lowercase.</summary>
    public string CommandLower => Command?.ToLowerInvariant() ?? "";

    /// <summary>Get the data size in bytes, with a default value.</summary>
    public int GetDataSizeBytesOrDefault(int defaultSize) => DataSizeBytes ?? defaultSize;

    /// <summary>Check if this is a SET command.</summary>
    public bool IsSetCommand => "set".Equals(Command, StringComparison.OrdinalIgnoreCase);

    /// <summary>Check if this is a GET command.</summary>
    public bool IsGetCommand => "get".Equals(Command, StringComparison.OrdinalIgnoreCase);

    /// <summary>Check if this is a PING command.</summary>
    public bool IsPingCommand => "ping".Equals(Command, StringComparison.OrdinalIgnoreCase);

    /// <summary>Validate the command configuration.</summary>
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Command))
            throw new ArgumentException("Command name is required");
        if (Weight < 0 || Weight > 1)
            throw new ArgumentException($"Command weight must be between 0 and 1, got {Weight}");
        if (IsSetCommand && (DataSizeBytes == null || DataSizeBytes <= 0))
            throw new ArgumentException("SET command requires positive data_size_bytes");
    }

    public override string ToString() =>
        $"CommandConfig{{command='{Command}', weight={Weight}{(DataSizeBytes.HasValue ? $", dataSizeBytes={DataSizeBytes}" : "")}}}";
}
