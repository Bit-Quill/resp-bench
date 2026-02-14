/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.command.impl;

import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.command.Command;
import io.valkey.javabenchmark.config.CommandConfig;
import io.valkey.javabenchmark.engine.KeyGenerator;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SET command implementation.
 *
 * @author Ilia Kolominsky
 */
public class SetCommand implements Command {
    private double weight = 1.0;
    private int dataSizeBytes = 256;
    private byte[] valueBuffer;

    @Override
    public String getName() { return "SET"; }

    @Override
    public String getDescription() { return "SET key value"; }

    @Override
    public void configure(CommandConfig config) {
        this.weight = config.getWeight();
        this.dataSizeBytes = config.getDataSizeBytesOrDefault(256);
        this.valueBuffer = new byte[dataSizeBytes];
        ThreadLocalRandom.current().nextBytes(valueBuffer);
    }

    @Override
    public CompletableFuture<CommandResult> execute(BenchmarkClient client, KeyGenerator keyGenerator) {
        byte[] key = keyGenerator.nextKey();
        
        return client.set(key, valueBuffer)
                .thenApply(timedResult -> 
                    CommandResult.success("SET", timedResult.getLatencyMicros()))
                .exceptionally(e -> 
                    // On error, we can't get the real latency from TimedResult, use 0
                    CommandResult.failure("SET", 0, e.getMessage()));
    }

    @Override
    public double getWeight() { return weight; }
}