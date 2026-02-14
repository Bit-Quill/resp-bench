/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark;

import io.valkey.javabenchmark.client.BenchmarkClientFactory;
import io.valkey.javabenchmark.command.CommandFactory;
import io.valkey.javabenchmark.config.*;
import io.valkey.javabenchmark.engine.BenchmarkEngine;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

/**
 * Main entry point for the Valkey Java Benchmark tool.
 *
 * @author Ilia Kolominsky
 */
@Command(name = "java-valkey-benchmark",
         mixinStandardHelpOptions = true,
         version = "1.0.0",
         description = "Benchmark engine for Java Valkey/Redis client libraries")
public class Main implements Callable<Integer> {

    @Option(names = {"-s", "--server"}, required = true,
            description = "Server endpoint (host:port)")
    private String server;

    @Option(names = {"-d", "--driver"}, required = true,
            description = "Path to driver configuration JSON")
    private String driverConfig;

    @Option(names = {"-w", "--workload"}, required = true,
            description = "Path to workload configuration JSON")
    private String workloadConfig;

    @Option(names = {"-m", "--metrics"}, required = true,
            description = "Path for metrics CSV output")
    private String metricsPath;

    @Option(names = {"--info"},
            description = "Show supported drivers and commands")
    private boolean showInfo;

    @Override
    public Integer call() throws Exception {
        if (showInfo) {
            printInfo();
            return 0;
        }

        // Parse server endpoint
        String[] parts = server.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6379;

        // Load configurations
        DriverConfig driver = ConfigLoader.loadDriverConfig(driverConfig);
        WorkloadConfig workload = ConfigLoader.loadWorkloadConfig(workloadConfig);

        // Create and run engine
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsPath);
        engine.run();

        return 0;
    }

    private void printInfo() {
        System.out.println();
        System.out.println("Valkey Java Benchmark Engine v1.0.0");
        System.out.println("====================================");
        System.out.println();

        System.out.println("Supported Drivers:");
        for (var driver : BenchmarkClientFactory.getRegisteredDrivers()) {
            System.out.printf("  - %-20s : %s%n", driver.driverId(), driver.description());
        }
        System.out.println();

        System.out.println("Supported Commands:");
        for (var cmd : CommandFactory.getRegisteredCommands()) {
            System.out.printf("  - %-10s : %s%n", cmd.name(), cmd.description());
        }
        System.out.println();

        System.out.println("Supported Key Generation Algorithms:");
        System.out.println("  - sequential_int : Sequential integers (0 to keys_count)");
        System.out.println("  - uniform_rand   : Uniform random distribution");
        System.out.println();

        System.out.println("Supported Completion Types:");
        System.out.println("  - duration : Run for specified seconds");
        System.out.println("  - requests : Run until request count reached");
        System.out.println();

        System.out.println("Usage:");
        System.out.println("  java -jar benchmark.jar \\");
        System.out.println("    --server localhost:6379 \\");
        System.out.println("    --driver configs/drivers/sample-jedis-cmd.json \\");
        System.out.println("    --workload configs/workloads/sample-benchmark.json \\");
        System.out.println("    --metrics output/metrics.csv");
        System.out.println();
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}