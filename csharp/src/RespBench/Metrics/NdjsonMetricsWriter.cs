/*
 * Copyright 2025 the original author or authors.
 */
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using HdrHistogram;
using HdrHistogram.Encoding;
using HdrHistogram.Utilities;

namespace RespBench.Metrics;

/// <summary>
/// Writes benchmark metrics to NDJSON format.
/// Includes native HDR histogram encoding with base64 payload.
/// </summary>
public class NdjsonMetricsWriter
{
    private readonly string _outputPath;

    private string? _commitId;
    private string? _driverId;
    private string? _primaryDriverVersion;
    private string? _secondaryDriverId;
    private string? _secondaryDriverVersion;

    public NdjsonMetricsWriter(string path)
    {
        _outputPath = path;
    }

    public void SetMetadata(string? commitId, string? driverId, string? primaryDriverVersion,
                           string? secondaryDriverId, string? secondaryDriverVersion)
    {
        _commitId = commitId;
        _driverId = driverId;
        _primaryDriverVersion = primaryDriverVersion;
        _secondaryDriverId = secondaryDriverId;
        _secondaryDriverVersion = secondaryDriverVersion;
    }

    public void WritePhaseResults(string phaseId, string status, int connections, MetricsCollector collector)
    {
        // Ensure parent directory exists
        var dir = Path.GetDirectoryName(_outputPath);
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);

        var root = BuildPhaseJson(phaseId, status, connections, collector);

        // Write as single line + newline
        string json = root.ToJsonString(new JsonSerializerOptions { WriteIndented = false });
        File.AppendAllText(_outputPath, json + Environment.NewLine);
    }

    private JsonObject BuildPhaseJson(string phaseId, string status, int connections, MetricsCollector collector)
    {
        var root = new JsonObject();

        // Metadata
        if (_commitId != null || _driverId != null)
        {
            var metadata = new JsonObject();
            if (_commitId != null) metadata["commit_id"] = _commitId;
            metadata["timestamp"] = DateTimeOffset.UtcNow.ToString("o");
            if (_driverId != null) metadata["driver_id"] = _driverId;
            if (_primaryDriverVersion != null) metadata["primary_driver_version"] = _primaryDriverVersion;
            if (_secondaryDriverId != null) metadata["secondary_driver_id"] = _secondaryDriverId;
            if (_secondaryDriverVersion != null) metadata["secondary_driver_version"] = _secondaryDriverVersion;
            root["metadata"] = metadata;
        }

        // Phase metadata
        var phase = new JsonObject
        {
            ["id"] = phaseId,
            ["status"] = status,
            ["start_timestamp"] = DateTimeOffset.FromUnixTimeMilliseconds(collector.StartTime).ToString("o"),
            ["finish_timestamp"] = DateTimeOffset.FromUnixTimeMilliseconds(collector.EndTime).ToString("o"),
            ["duration_ms"] = collector.DurationMillis,
            ["connections"] = connections
        };
        root["phase"] = phase;

        // Totals
        var totals = new JsonObject
        {
            ["requests"] = collector.TotalRequests,
            ["errors"] = collector.TotalErrors
        };
        root["totals"] = totals;

        // Per-command metrics
        var metrics = new JsonObject();
        foreach (var (cmdName, cmdMetrics) in collector.AllMetrics)
        {
            var cmdNode = new JsonObject
            {
                ["requests"] = cmdMetrics.Requests,
                ["errors"] = cmdMetrics.Errors
            };

            var latency = new JsonObject
            {
                ["unit"] = "us",
                ["count"] = cmdMetrics.Histogram.TotalCount
            };

            var summary = new JsonObject
            {
                ["min"] = cmdMetrics.Histogram.TotalCount > 0 ? cmdMetrics.Histogram.GetValueAtPercentile(0) : 0,
                ["p50"] = cmdMetrics.P50,
                ["p95"] = cmdMetrics.P95,
                ["p99"] = cmdMetrics.P99,
                ["p999"] = cmdMetrics.P999,
                ["max"] = cmdMetrics.Max
            };
            latency["summary"] = summary;

            var hdr = new JsonObject
            {
                ["format"] = "hdr",
                ["sigfig"] = 3,
                ["payload_b64"] = EncodeHistogram(cmdMetrics.Histogram)
            };
            latency["hdr"] = hdr;

            cmdNode["latency"] = latency;
            metrics[cmdName] = cmdNode;
        }
        root["metrics"] = metrics;

        return root;
    }

    private static string EncodeHistogram(HistogramBase histogram)
    {
        try
        {
            // Use HdrHistogram.NET's direct encoding API (matches Java's encodeIntoCompressedByteBuffer)
            int neededCapacity = histogram.GetNeededByteBufferCapacity();
            var buffer = ByteBuffer.Allocate(neededCapacity);
            int bytesWritten = histogram.Encode(buffer, HistogramEncoderV2.Instance);

            // Extract the written bytes: reset position, read one at a time
            buffer.Position = 0;
            byte[] compressedBytes = new byte[bytesWritten];
            for (int i = 0; i < bytesWritten; i++)
                compressedBytes[i] = buffer.Get();

            return Convert.ToBase64String(compressedBytes);
        }
        catch
        {
            return "";
        }
    }
}
