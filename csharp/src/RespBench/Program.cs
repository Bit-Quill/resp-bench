/*
 * Copyright 2025 the original author or authors.
 */
using System.CommandLine;
using RespBench.Client;
using RespBench.Command;
using RespBench.Config;
using RespBench.Engine;

namespace RespBench;

/// <summary>
/// Main entry point for the C# Benchmark tool.
/// </summary>
public class Program
{
    public static async Task<int> Main(string[] args)
    {
        var serverOption = new Option<string>("--server", "Server endpoint (host:port)") { IsRequired = true };
        serverOption.AddAlias("-s");

        var driverOption = new Option<string>("--driver", "Path to driver configuration JSON") { IsRequired = true };
        driverOption.AddAlias("-d");

        var workloadOption = new Option<string>("--workload", "Path to workload configuration JSON") { IsRequired = true };
        workloadOption.AddAlias("-w");

        var metricsOption = new Option<string>("--metrics", "Path for metrics NDJSON output") { IsRequired = true };
        metricsOption.AddAlias("-m");

        var commitIdOption = new Option<string?>("--commit-id", "Git commit ID for metadata");
        var infoOption = new Option<bool>("--info", "Show supported drivers and commands");

        var rootCommand = new RootCommand("C# benchmark engine for Valkey/Redis client libraries")
        {
            serverOption, driverOption, workloadOption, metricsOption, commitIdOption, infoOption
        };

        rootCommand.SetHandler(async (string server, string driverPath, string workloadPath,
                                       string metricsPath, string? commitId, bool showInfo) =>
        {
            if (showInfo)
            {
                PrintInfo();
                return;
            }

            // Parse server endpoint
            var parts = server.Split(':');
            string host = parts[0];
            int port = parts.Length > 1 ? int.Parse(parts[1]) : 6379;

            // Load configurations
            var driver = ConfigLoader.LoadDriverConfig(driverPath);
            var workload = ConfigLoader.LoadWorkloadConfig(workloadPath);

            // Determine commit ID
            if (string.IsNullOrEmpty(commitId))
                commitId = BuildInfo.GetCommitSummary();

            // Create and run engine
            var engine = new BenchmarkEngine(host, port, driver, workload, metricsPath, commitId);
            await engine.Run().ConfigureAwait(false);

        }, serverOption, driverOption, workloadOption, metricsOption, commitIdOption, infoOption);

        return await rootCommand.InvokeAsync(args);
    }

    private static void PrintInfo()
    {
        string version = BuildInfo.GetCommitSummary() ?? "dev";
        Console.WriteLine();
        Console.WriteLine($"Valkey C# Benchmark Engine ({version})");
        Console.WriteLine("============================================");
        Console.WriteLine();

        Console.WriteLine("Supported Drivers:");
        foreach (var driver in BenchmarkClientFactory.GetRegisteredDrivers())
            Console.WriteLine($"  - {driver.DriverId,-25} : {driver.Description}");
        Console.WriteLine();

        Console.WriteLine("Supported Commands:");
        foreach (var cmd in CommandFactory.GetRegisteredCommands())
            Console.WriteLine($"  - {cmd.Name,-10} : {cmd.Description}");
        Console.WriteLine();

        Console.WriteLine("Supported Key Generation Algorithms:");
        Console.WriteLine("  - sequential_int : Sequential integers (0 to keys_count)");
        Console.WriteLine("  - uniform_rand   : Uniform random distribution");
        Console.WriteLine();

        Console.WriteLine("Supported Completion Types:");
        Console.WriteLine("  - duration : Run for specified seconds");
        Console.WriteLine("  - requests : Run until request count reached");
        Console.WriteLine();
    }
}
