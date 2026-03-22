/*
 * Copyright 2025 the original author or authors.
 */
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace RespBench.Config;

/// <summary>
/// Utility class for loading configuration files.
/// </summary>
public static class ConfigLoader
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    /// <summary>Load driver configuration from a JSON file.</summary>
    public static DriverConfig LoadDriverConfig(string path)
    {
        var content = File.ReadAllText(path);
        var config = JsonSerializer.Deserialize<DriverConfig>(content, JsonOptions)
            ?? throw new ConfigurationException($"Failed to deserialize driver configuration from {path}");

        if (string.IsNullOrWhiteSpace(config.DriverId))
            throw new ConfigurationException("Driver configuration must have a driver_id");
        if (string.IsNullOrWhiteSpace(config.Mode))
            throw new ConfigurationException("Driver configuration must have a mode");

        return config;
    }

    /// <summary>Load workload configuration from a JSON file.</summary>
    public static WorkloadConfig LoadWorkloadConfig(string path)
    {
        var content = File.ReadAllText(path);
        var config = JsonSerializer.Deserialize<WorkloadConfig>(content, JsonOptions)
            ?? throw new ConfigurationException($"Failed to deserialize workload configuration from {path}");

        ValidateWorkloadConfig(config);
        return config;
    }

    /// <summary>Parse a driver configuration from JSON string.</summary>
    public static DriverConfig ParseDriverConfig(string json)
    {
        var config = JsonSerializer.Deserialize<DriverConfig>(json, JsonOptions)
            ?? throw new ConfigurationException("Failed to parse driver configuration");

        if (string.IsNullOrWhiteSpace(config.DriverId))
            throw new ConfigurationException("Driver configuration must have a driver_id");

        return config;
    }

    /// <summary>Parse a workload configuration from JSON string.</summary>
    public static WorkloadConfig ParseWorkloadConfig(string json)
    {
        var config = JsonSerializer.Deserialize<WorkloadConfig>(json, JsonOptions)
            ?? throw new ConfigurationException("Failed to parse workload configuration");

        ValidateWorkloadConfig(config);
        return config;
    }

    private static void ValidateWorkloadConfig(WorkloadConfig config)
    {
        if (config.Phases == null || config.Phases.Count == 0)
            throw new ConfigurationException("Workload must have at least one phase");

        for (int i = 0; i < config.Phases.Count; i++)
        {
            var phase = config.Phases[i];
            try
            {
                phase.Validate();
            }
            catch (ArgumentException e)
            {
                throw new ConfigurationException(
                    $"Invalid configuration in phase {i + 1} ({phase.Id}): {e.Message}", e);
            }
        }
    }
}

/// <summary>Exception thrown when configuration loading or validation fails.</summary>
public class ConfigurationException : Exception
{
    public ConfigurationException(string message) : base(message) { }
    public ConfigurationException(string message, Exception innerException) : base(message, innerException) { }
}
