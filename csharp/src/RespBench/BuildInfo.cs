/*
 * Copyright 2025 the original author or authors.
 */
using System.Diagnostics;
using System.Reflection;

namespace RespBench;

/// <summary>
/// Provides build-time information such as git commit ID.
/// </summary>
public static class BuildInfo
{
    private static readonly string? CachedCommitSummary;

    static BuildInfo()
    {
        CachedCommitSummary = DetectGitCommit();
    }

    public static string? GetCommitSummary() => CachedCommitSummary;

    private static string? DetectGitCommit()
    {
        try
        {
            var psi = new ProcessStartInfo("git", "rev-parse --short HEAD")
            {
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var proc = Process.Start(psi);
            if (proc == null) return null;
            string shortId = proc.StandardOutput.ReadToEnd().Trim();
            proc.WaitForExit(5000);
            if (string.IsNullOrEmpty(shortId)) return null;

            // Check dirty
            var dirtyPsi = new ProcessStartInfo("git", "diff --quiet HEAD")
            {
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var dirtyProc = Process.Start(dirtyPsi);
            dirtyProc?.WaitForExit(5000);
            if (dirtyProc?.ExitCode != 0)
                shortId += "-dirty";

            return shortId;
        }
        catch
        {
            return null;
        }
    }
}
