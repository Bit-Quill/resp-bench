/*
 * Copyright 2025 the original author or authors.
 */
using System.Text.Json.Serialization;

namespace RespBench.Config;

/// <summary>
/// Configuration model for phase completion criteria.
/// </summary>
public class CompletionConfig
{
    public const string TypeDuration = "duration";
    public const string TypeRequests = "requests";

    [JsonPropertyName("type")]
    public string Type { get; set; } = "";

    [JsonPropertyName("seconds")]
    public long? Seconds { get; set; }

    [JsonPropertyName("requests")]
    public long? Requests { get; set; }

    // Convenience methods

    /// <summary>Check if completion is duration-based.</summary>
    public bool IsDurationBased => TypeDuration.Equals(Type, StringComparison.OrdinalIgnoreCase);

    /// <summary>Check if completion is request-based.</summary>
    public bool IsRequestBased => TypeRequests.Equals(Type, StringComparison.OrdinalIgnoreCase);

    /// <summary>Get the duration in seconds.</summary>
    public long DurationSeconds => Seconds ?? 0L;

    /// <summary>Get the target request count.</summary>
    public long TotalRequests => Requests ?? 0L;

    /// <summary>Validate the completion configuration.</summary>
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Type))
            throw new ArgumentException("Completion type is required");
        if (IsDurationBased)
        {
            if (Seconds == null || Seconds <= 0)
                throw new ArgumentException("Duration-based completion requires positive seconds value");
        }
        else if (IsRequestBased)
        {
            if (Requests == null || Requests <= 0)
                throw new ArgumentException("Request-based completion requires positive requests value");
        }
        else
        {
            throw new ArgumentException(
                $"Unknown completion type: {Type}. Supported types: {TypeDuration}, {TypeRequests}");
        }
    }

    public override string ToString() =>
        IsDurationBased ? $"CompletionConfig{{type='duration', seconds={Seconds}}}"
        : IsRequestBased ? $"CompletionConfig{{type='requests', requests={Requests}}}"
        : $"CompletionConfig{{type='{Type}'}}";
}
