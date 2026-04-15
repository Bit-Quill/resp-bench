/*
 * Copyright 2025 the original author or authors.
 */
using Microsoft.Extensions.Logging;
using RespBench.Client;
using RespBench.Command;
using RespBench.Config;
using RespBench.Metrics;

namespace RespBench.Engine;

/// <summary>
/// Main benchmark engine that orchestrates the benchmark execution.
/// Uses a Task-per-client architecture where each client connection
/// is owned by a long-lived Task that runs a tight request loop.
/// </summary>
public class BenchmarkEngine
{
    private const int DefaultPipelineDepth = 1;
    private const int ProgressLogIntervalSeconds = 10;

    private readonly string _host;
    private readonly int _port;
    private readonly DriverConfig _driverConfig;
    private readonly WorkloadConfig _workloadConfig;
    private readonly NdjsonMetricsWriter _metricsWriter;
    private readonly string? _commitId;
    private readonly ILogger<BenchmarkEngine> _logger;

    public BenchmarkEngine(string host, int port, DriverConfig driverConfig,
                          WorkloadConfig workloadConfig, string metricsPath,
                          string? commitId = null, int? commandIssuerThreads = null)
    {
        _host = host;
        _port = port;
        _driverConfig = driverConfig;
        _workloadConfig = workloadConfig;
        _metricsWriter = new NdjsonMetricsWriter(metricsPath);
        _commitId = commitId;

        using var loggerFactory = LoggerFactory.Create(b => b.AddConsole().SetMinimumLevel(LogLevel.Information));
        _logger = loggerFactory.CreateLogger<BenchmarkEngine>();
    }

    public async Task Run()
    {
        Console.WriteLine($"Starting benchmark: {_workloadConfig.BenchmarkProfileData?.Name}");
        Console.WriteLine($"Driver: {_driverConfig.DriverId}, Mode: {_driverConfig.Mode}");
        Console.WriteLine($"Server: {_host}:{_port}");

        SetupMetadata();

        foreach (var phase in _workloadConfig.Phases)
        {
            await ExecutePhase(phase).ConfigureAwait(false);
        }

        Console.WriteLine("Benchmark completed");
    }

    private void SetupMetadata()
    {
        try
        {
            using var sampleClient = BenchmarkClientFactory.CreateAndConnect(_host, _port, _driverConfig);
            string driverId = _driverConfig.DriverId;
            string primaryVersion = sampleClient.DriverVersion;
            string? secondaryDriverId = _driverConfig.SecondaryDriverId;
            string? secondaryVersion = sampleClient.SecondaryDriverVersion;

            _metricsWriter.SetMetadata(_commitId, driverId, primaryVersion, secondaryDriverId, secondaryVersion);
            Console.WriteLine($"Metadata: commit={_commitId ?? "N/A"}, driver={driverId}, version={primaryVersion}");
        }
        catch (Exception e)
        {
            Console.WriteLine($"Warning: Failed to get driver version for metadata: {e.Message}");
            _metricsWriter.SetMetadata(_commitId, _driverConfig.DriverId, "unknown",
                                       _driverConfig.SecondaryDriverId, null);
        }
    }

    private async Task ExecutePhase(PhaseConfig phase)
    {
        Console.WriteLine($"=== Starting phase: {phase.Id} ({phase.Description}) ===");

        int pipelineDepth = phase.PipelineDepth > 0 ? phase.PipelineDepth : DefaultPipelineDepth;

        var clients = await CreateClients(phase).ConfigureAwait(false);
        var commands = CommandFactory.CreateAll(phase.Commands);
        var keyGenerator = KeyGenerator.Create(phase.Keyspace);
        var rateLimiter = phase.HasRpsLimit ? RateLimiter.Create(phase.RpsLimit) : null;
        var metrics = new MetricsCollector();

        await WarmupClients(clients, phase.WarmupRequests).ConfigureAwait(false);

        string status = await ExecuteWorkload(phase, clients, commands, keyGenerator,
                                               rateLimiter, metrics, pipelineDepth).ConfigureAwait(false);

        _metricsWriter.WritePhaseResults(phase.Id, status, phase.Connections, metrics);
        CloseClients(clients);

        Console.WriteLine($"=== Phase {phase.Id} completed: {status} ===");
    }

    private async Task<List<IBenchmarkClient>> CreateClients(PhaseConfig phase)
    {
        Console.WriteLine($"Creating {phase.Connections} connections...");
        var clients = new List<IBenchmarkClient>();
        var cpsLimiter = phase.HasCpsLimit ? RateLimiter.Create(phase.CpsLimit) : null;

        for (int i = 0; i < phase.Connections; i++)
        {
            if (cpsLimiter != null) await cpsLimiter.Acquire().ConfigureAwait(false);
            var client = BenchmarkClientFactory.CreateAndConnect(_host, _port, _driverConfig);
            clients.Add(client);
            if ((i + 1) % 50 == 0)
                Console.WriteLine($"Created {i + 1}/{phase.Connections} connections");
        }

        Console.WriteLine($"All {clients.Count} connections established");
        return clients;
    }

    private async Task WarmupClients(List<IBenchmarkClient> clients, int warmupRequestsPerClient)
    {
        if (warmupRequestsPerClient <= 0) return;
        Console.WriteLine($"Warming up {clients.Count} clients with {warmupRequestsPerClient} PING(s) each...");

        foreach (var client in clients) client.SetWarmupMode(true);

        try
        {
            var tasks = clients.Select(client => Task.Run(async () =>
            {
                for (int r = 0; r < warmupRequestsPerClient; r++)
                    await client.Ping().ConfigureAwait(false);
            })).ToArray();

            await Task.WhenAll(tasks).ConfigureAwait(false);
        }
        finally
        {
            foreach (var client in clients) client.SetWarmupMode(false);
        }

        Console.WriteLine("Warmup completed");
    }

    // Shared mutable state for worker tasks (avoids ref params in async methods)
    private class WorkloadState
    {
        public int Running = 1;
        public long RequestCount;
    }

    private async Task<string> ExecuteWorkload(PhaseConfig phase, List<IBenchmarkClient> clients,
                                                List<ICommand> commands, KeyGenerator keyGenerator,
                                                RateLimiter? rateLimiter, MetricsCollector metrics,
                                                int pipelineDepth)
    {
        var completion = phase.Completion;
        var state = new WorkloadState();
        int workerCount = clients.Count;

        Console.WriteLine($"Spawning {workerCount} worker tasks (pipeline_depth={pipelineDepth})");

        long totalTargetRequests = completion.IsRequestBased ? completion.TotalRequests : -1;
        long endTimeMs = completion.IsDurationBased
            ? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + (completion.DurationSeconds * 1000)
            : long.MaxValue;

        metrics.Start();
        long startTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        try
        {
            var workerTasks = new Task[workerCount];

            for (int i = 0; i < workerCount; i++)
            {
                int workerIndex = i;
                var client = clients[i];
                var workerKeyGen = keyGenerator.ForkForThread(workerIndex);
                var workerSelector = new CommandSelector(commands);

                workerTasks[i] = Task.Run(async () =>
                {
                    try
                    {
                        if (pipelineDepth <= 1)
                        {
                            await RunSyncLoop(client, workerSelector, workerKeyGen, rateLimiter,
                                metrics, state, totalTargetRequests, endTimeMs)
                                .ConfigureAwait(false);
                        }
                        else
                        {
                            await RunPipelinedLoop(client, workerSelector, workerKeyGen, rateLimiter,
                                metrics, state, totalTargetRequests, endTimeMs, pipelineDepth)
                                .ConfigureAwait(false);
                        }
                    }
                    catch (Exception)
                    {
                        Interlocked.Exchange(ref state.Running, 0);
                        throw;
                    }
                });
            }

            // Progress logging
            var allDone = Task.WhenAll(workerTasks);
            long lastLogTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            while (!allDone.IsCompleted)
            {
                try { await allDone.WaitAsync(TimeSpan.FromMilliseconds(100)).ConfigureAwait(false); }
                catch (TimeoutException) { }
                catch { break; }

                lastLogTime = LogProgressIfNeeded(Interlocked.Read(ref state.RequestCount), totalTargetRequests,
                                                   lastLogTime, startTime);
            }

            // Check for worker failures
            bool hasFailures = false;
            foreach (var task in workerTasks)
            {
                if (task.IsFaulted)
                {
                    Console.WriteLine($"Worker failed: {task.Exception?.InnerException?.Message}");
                    hasFailures = true;
                }
            }

            if (hasFailures) return "ERROR";
        }
        catch (Exception e)
        {
            Console.WriteLine($"Error during workload execution: {e.Message}");
            return "ERROR";
        }
        finally
        {
            metrics.Stop();
        }

        Console.WriteLine($"All operations completed. Total requests: {Interlocked.Read(ref state.RequestCount)}");
        return "COMPLETED";
    }

    private async Task RunSyncLoop(IBenchmarkClient client, CommandSelector selector,
                                    KeyGenerator keyGen, RateLimiter? rateLimiter,
                                    MetricsCollector metrics, WorkloadState state,
                                    long totalTargetRequests, long endTimeMs)
    {
        while (Volatile.Read(ref state.Running) == 1)
        {
            if (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() >= endTimeMs) break;
            if (totalTargetRequests > 0)
            {
                long claimed = Interlocked.Increment(ref state.RequestCount) - 1;
                if (claimed >= totalTargetRequests)
                {
                    Interlocked.Decrement(ref state.RequestCount);
                    break;
                }
            }

            if (rateLimiter != null) await rateLimiter.Acquire().ConfigureAwait(false);

            var command = selector.Select();
            var result = await command.Execute(client, keyGen).ConfigureAwait(false);
            metrics.Record(result);

            if (totalTargetRequests <= 0)
                Interlocked.Increment(ref state.RequestCount);
        }
    }

    private async Task RunPipelinedLoop(IBenchmarkClient client, CommandSelector selector,
                                         KeyGenerator keyGen, RateLimiter? rateLimiter,
                                         MetricsCollector metrics, WorkloadState state,
                                         long totalTargetRequests, long endTimeMs, int pipelineDepth)
    {
        var pending = new List<Task<CommandResult>>(pipelineDepth);

        // Fill pipeline
        for (int i = 0; i < pipelineDepth && Volatile.Read(ref state.Running) == 1; i++)
        {
            if (!ClaimAndSubmit(client, selector, keyGen, rateLimiter, state,
                               totalTargetRequests, endTimeMs, pending))
                break;
        }

        while (pending.Count > 0 && Volatile.Read(ref state.Running) == 1)
        {
            var completed = await Task.WhenAny(pending).ConfigureAwait(false);
            pending.Remove(completed);

            try
            {
                var result = await completed.ConfigureAwait(false);
                metrics.Record(result);
            }
            catch (Exception e)
            {
                metrics.Record(CommandResult.Failure("UNKNOWN", e.Message));
            }

            if (totalTargetRequests <= 0) Interlocked.Increment(ref state.RequestCount);

            if (Volatile.Read(ref state.Running) == 1)
                ClaimAndSubmit(client, selector, keyGen, rateLimiter, state,
                              totalTargetRequests, endTimeMs, pending);
        }
    }

    private bool ClaimAndSubmit(IBenchmarkClient client, CommandSelector selector,
                                 KeyGenerator keyGen, RateLimiter? rateLimiter,
                                 WorkloadState state, long totalTargetRequests,
                                 long endTimeMs, List<Task<CommandResult>> pending)
    {
        if (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() >= endTimeMs) return false;
        if (totalTargetRequests > 0)
        {
            long claimed = Interlocked.Increment(ref state.RequestCount) - 1;
            if (claimed >= totalTargetRequests)
            {
                Interlocked.Decrement(ref state.RequestCount);
                return false;
            }
        }

        if (rateLimiter != null) rateLimiter.Acquire().GetAwaiter().GetResult();

        var command = selector.Select();
        pending.Add(command.Execute(client, keyGen));
        return true;
    }

    private void CloseClients(List<IBenchmarkClient> clients)
    {
        Console.WriteLine($"Closing {clients.Count} connections...");
        foreach (var client in clients)
        {
            try { client.Dispose(); }
            catch (Exception e) { Console.WriteLine($"Error closing client: {e.Message}"); }
        }
    }

    private long LogProgressIfNeeded(long current, long target, long lastLogTime, long startTime)
    {
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (now - lastLogTime < ProgressLogIntervalSeconds * 1000) return lastLogTime;

        long elapsedMs = now - startTime;
        long rate = elapsedMs > 0 ? (current * 1000 / elapsedMs) : 0;

        if (target > 0)
        {
            double percent = current * 100.0 / target;
            Console.WriteLine($"Progress: {current}/{target} requests ({percent:F1} %) - {rate} req/s");
        }
        else
        {
            Console.WriteLine($"Progress: {current} requests - {rate} req/s");
        }

        return now;
    }
}
