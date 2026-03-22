/*
 * Copyright 2025 the original author or authors.
 */
using System.Text.Json.Serialization;

namespace RespBench.Config;

/// <summary>
/// Configuration model for a benchmark phase.
/// </summary>
public class PhaseConfig
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("description")]
    public string? Description { get; set; }

    [JsonPropertyName("connections")]
    public int Connections { get; set; }

    [JsonPropertyName("cps_limit")]
    public int CpsLimit { get; set; } = -1;

    [JsonPropertyName("rps_limit")]
    public int RpsLimit { get; set; } = -1;

    [JsonPropertyName("pipeline_depth")]
    public int PipelineDepth { get; set; } = 1;

    [JsonPropertyName("warmup_requests")]
    public int WarmupRequests { get; set; } = 1;

    [JsonPropertyName("completion")]
    public CompletionConfig Completion { get; set; } = new();

    [JsonPropertyName("keyspace")]
    public KeyspaceConfig Keyspace { get; set; } = new();

    [JsonPropertyName("commands")]
    public List<CommandConfig> Commands { get; set; } = new();

    // Convenience methods

    /// <summary>Check if connection rate limiting is enabled.</summary>
    public bool HasCpsLimit => CpsLimit > 0;

    /// <summary>Check if request rate limiting is enabled.</summary>
    public bool HasRpsLimit => RpsLimit > 0;

    /// <summary>
    /// Validate the phase configuration.
    /// </summary>
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Id))
            throw new ArgumentException("Phase id is required");
        if (Connections <= 0)
            throw new ArgumentException("Phase connections must be positive");
        if (Completion == null)
            throw new ArgumentException("Phase completion config is required");
        Completion.Validate();
        if (Keyspace == null)
            throw new ArgumentException("Phase keyspace config is required");
        Keyspace.Validate();
        if (Commands == null || Commands.Count == 0)
            throw new ArgumentException("Phase must have at least one command");

        // Validate command weights sum to 1
        double totalWeight = Commands.Sum(c => c.Weight);
        if (Math.Abs(totalWeight - 1.0) > 0.001)
            throw new ArgumentException($"Command weights must sum to 1.0, got {totalWeight}");

        foreach (var cmd in Commands)
            cmd.Validate();
    }

    public override string ToString() =>
        $"PhaseConfig{{id='{Id}', connections={Connections}, pipelineDepth={PipelineDepth}, warmupRequests={WarmupRequests}, rpsLimit={RpsLimit}, completion={Completion}, commandsCount={Commands.Count}}}";
}
