/*
 * Copyright 2025 the original author or authors.
 */
using System.Text.Json.Serialization;

namespace RespBench.Config;

/// <summary>
/// Configuration model for workload settings.
/// Maps to the workload JSON configuration files.
/// </summary>
public class WorkloadConfig
{
    [JsonPropertyName("schema_version")]
    public string? SchemaVersion { get; set; }

    [JsonPropertyName("benchmark_profile")]
    public BenchmarkProfile? BenchmarkProfileData { get; set; }

    [JsonPropertyName("phases")]
    public List<PhaseConfig> Phases { get; set; } = new();

    public override string ToString() =>
        $"WorkloadConfig{{schemaVersion='{SchemaVersion}', benchmarkProfile={BenchmarkProfileData}, phasesCount={Phases.Count}}}";
}

/// <summary>Benchmark profile metadata.</summary>
public class BenchmarkProfile
{
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("description")]
    public string? Description { get; set; }

    [JsonPropertyName("version")]
    public string? Version { get; set; }

    public override string ToString() =>
        $"BenchmarkProfile{{name='{Name}', version='{Version}'}}";
}
