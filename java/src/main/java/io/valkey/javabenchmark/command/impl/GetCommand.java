/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.command.impl;

import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.command.Command;
import io.valkey.javabenchmark.config.CommandConfig;
import io.valkey.javabenchmark.engine.KeyGenerator;

import java.util.concurrent.CompletableFuture;

/**
 * GET command implementation.
 */
public class GetCommand implements Command {
    private double weight = 1.0;

    @Override
    public String getName() { return "GET"; }

    @Override
    public String getDescription() { return "GET key"; }

    @Override
    public void configure(CommandConfig config) {
        this.weight = config.getWeight();
    }

    @Override
    public CompletableFuture<CommandResult> execute(BenchmarkClient client, KeyGenerator keyGenerator) {
        byte[] key = keyGenerator.nextKey();
        
        return client.get(key)
                .thenApply(timedResult -> 
                    CommandResult.success("GET", timedResult.getLatencyMicros()))
                .exceptionally(e -> 
                    // On error, we can't get the real latency from TimedResult, use 0
                    CommandResult.failure("GET", 0, e.getMessage()));
    }

    @Override
    public double getWeight() { return weight; }
}