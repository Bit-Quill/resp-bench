/*
 * Copyright 2025 the original author or authors.
 */
using System.Text.Json.Serialization;

namespace RespBench.Config;

/// <summary>
/// Configuration model for driver settings.
/// Maps to the driver JSON configuration files.
/// </summary>
public class DriverConfig
{
    [JsonPropertyName("schema_version")]
    public string? SchemaVersion { get; set; }

    [JsonPropertyName("description")]
    public string? Description { get; set; }

    [JsonPropertyName("driver_id")]
    public string DriverId { get; set; } = "";

    [JsonPropertyName("mode")]
    public string Mode { get; set; } = "";

    [JsonPropertyName("auth")]
    public AuthConfig? Auth { get; set; }

    [JsonPropertyName("tls")]
    public TlsConfig? Tls { get; set; }

    [JsonPropertyName("command_timeout_ms")]
    public int? CommandTimeoutMs { get; set; }

    [JsonPropertyName("specific_driver_config")]
    public Dictionary<string, object>? SpecificDriverConfig { get; set; }

    // Convenience methods

    /// <summary>Check if this is a cluster mode configuration.</summary>
    public bool IsClusterMode => "cluster".Equals(Mode, StringComparison.OrdinalIgnoreCase);

    /// <summary>Check if TLS is enabled.</summary>
    public bool IsTlsEnabled => Tls != null;

    /// <summary>Check if authentication is configured.</summary>
    public bool HasAuth => Auth?.Password != null && !string.IsNullOrEmpty(Auth.Password);

    /// <summary>Check if connection pooling is enabled in specific_driver_config.</summary>
    public bool IsUsePooling => GetSpecificBool("use_pooling", false);

    /// <summary>Get connection pool size from specific_driver_config (default 8).</summary>
    public int PoolSize => GetSpecificInt("pool_size", 8);

    /// <summary>Get the secondary driver ID for composite drivers.</summary>
    public string? SecondaryDriverId
    {
        get
        {
            if (SpecificDriverConfig == null) return null;
            if (SpecificDriverConfig.TryGetValue("secondary_driver_id", out var value))
                return value?.ToString();
            if (SpecificDriverConfig.TryGetValue("scondary_driver_id", out value))
                return value?.ToString();
            return null;
        }
    }

    /// <summary>Get secondary driver configuration map.</summary>
    public Dictionary<string, object>? SecondaryDriverConfig
    {
        get
        {
            if (SpecificDriverConfig?.TryGetValue("secondary_driver_config", out var value) == true
                && value is Dictionary<string, object> dict)
                return dict;
            return null;
        }
    }

    private bool GetSpecificBool(string key, bool defaultValue)
    {
        if (SpecificDriverConfig?.TryGetValue(key, out var value) != true) return defaultValue;
        if (value is bool b) return b;
        if (value is System.Text.Json.JsonElement je && je.ValueKind == System.Text.Json.JsonValueKind.True) return true;
        if (value is System.Text.Json.JsonElement je2 && je2.ValueKind == System.Text.Json.JsonValueKind.False) return false;
        if (value is string s) return bool.TryParse(s, out var parsed) && parsed;
        return defaultValue;
    }

    private int GetSpecificInt(string key, int defaultValue)
    {
        if (SpecificDriverConfig?.TryGetValue(key, out var value) != true) return defaultValue;
        if (value is int i) return i;
        if (value is long l) return (int)l;
        if (value is double d) return (int)d;
        if (value is System.Text.Json.JsonElement je && je.TryGetInt32(out var jInt)) return jInt;
        if (value is string s && int.TryParse(s, out var parsed)) return parsed;
        return defaultValue;
    }

    internal double GetSpecificDouble(string key, double defaultValue)
    {
        if (SpecificDriverConfig?.TryGetValue(key, out var value) != true) return defaultValue;
        if (value is double d) return d;
        if (value is float f) return f;
        if (value is int i) return i;
        if (value is long l) return l;
        if (value is System.Text.Json.JsonElement je && je.TryGetDouble(out var jd)) return jd;
        if (value is string s && double.TryParse(s, out var parsed)) return parsed;
        return defaultValue;
    }

    internal long GetSpecificLong(string key, long defaultValue)
    {
        if (SpecificDriverConfig?.TryGetValue(key, out var value) != true) return defaultValue;
        if (value is long l) return l;
        if (value is int i) return i;
        if (value is double d) return (long)d;
        if (value is System.Text.Json.JsonElement je && je.TryGetInt64(out var jl)) return jl;
        if (value is string s && long.TryParse(s, out var parsed)) return parsed;
        return defaultValue;
    }

    internal string? GetSpecificString(string key)
    {
        if (SpecificDriverConfig?.TryGetValue(key, out var value) != true) return null;
        if (value is System.Text.Json.JsonElement je && je.ValueKind == System.Text.Json.JsonValueKind.String)
            return je.GetString();
        return value?.ToString();
    }

    public override string ToString() =>
        $"DriverConfig{{driverId='{DriverId}', mode='{Mode}', tlsEnabled={IsTlsEnabled}, hasAuth={HasAuth}}}";
}

/// <summary>Authentication configuration.</summary>
public class AuthConfig
{
    [JsonPropertyName("username")]
    public string? Username { get; set; }

    [JsonPropertyName("password")]
    public string? Password { get; set; }
}

/// <summary>TLS configuration.</summary>
public class TlsConfig
{
    [JsonPropertyName("cert_path")]
    public string? CertPath { get; set; }

    [JsonPropertyName("key_path")]
    public string? KeyPath { get; set; }

    [JsonPropertyName("ca_cert_path")]
    public string? CaCertPath { get; set; }

    [JsonPropertyName("verify_peer")]
    public bool? VerifyPeer { get; set; }
}
