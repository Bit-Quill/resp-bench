/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.engine;

import io.valkey.javabenchmark.config.KeyspaceConfig;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Key generator for benchmark operations.
 *
 * @author Ilia Kolominsky
 */
public class KeyGenerator {
    private final KeyspaceConfig config;
    private final AtomicLong sequentialCounter;
    private final Random random;
    private final String keyPrefix;
    private final int keySizeBytes;
    private final int keysCount;
    private final String formatPattern;

    public KeyGenerator(KeyspaceConfig config) {
        this(config, 0);
    }

    /**
     * Creates a KeyGenerator with a thread-specific seed offset.
     * 
     * <p>For uniform_rand, the seed is offset by threadIndex to ensure each thread
     * generates a different random sequence. For sequential_int, the counter is shared
     * via AtomicLong so threads naturally interleave.</p>
     *
     * @param config the keyspace configuration
     * @param threadIndex the thread index for seed diversification (0 for single-threaded)
     */
    public KeyGenerator(KeyspaceConfig config, int threadIndex) {
        this.config = config;
        this.keyPrefix = config.getEffectiveKeyPrefix();
        this.keySizeBytes = config.getKeySizeBytes();
        this.keysCount = config.getKeysCount();
        this.sequentialCounter = new AtomicLong(0);
        this.random = new Random(config.getSeedValue() + threadIndex);
        this.formatPattern = "%0" + Math.max(1, keySizeBytes - keyPrefix.length()) + "d";
    }

    /**
     * Creates a KeyGenerator that shares the same sequential counter as this one,
     * but with an independent Random instance seeded differently.
     * 
     * <p>This is used for parallel command issuers: each thread gets its own
     * KeyGenerator with its own Random, but sequential counters are shared
     * to avoid duplicate sequential keys.</p>
     *
     * @param threadIndex the thread index for seed diversification
     * @return a new KeyGenerator sharing this instance's sequential counter
     */
    public KeyGenerator forkForThread(int threadIndex) {
        return new KeyGenerator(config, sequentialCounter, threadIndex);
    }

    /**
     * Internal constructor for forking with a shared sequential counter.
     */
    private KeyGenerator(KeyspaceConfig config, AtomicLong sharedCounter, int threadIndex) {
        this.config = config;
        this.keyPrefix = config.getEffectiveKeyPrefix();
        this.keySizeBytes = config.getKeySizeBytes();
        this.keysCount = config.getKeysCount();
        this.sequentialCounter = sharedCounter; // Share counter across threads
        this.random = new Random(config.getSeedValue() + threadIndex);
        this.formatPattern = "%0" + Math.max(1, keySizeBytes - keyPrefix.length()) + "d";
    }

    public byte[] nextKey() {
        long keyIndex = config.isSequentialInt() 
                ? sequentialCounter.getAndIncrement() % keysCount
                : random.nextInt(keysCount);
        
        String keyStr = keyPrefix + String.format(formatPattern, keyIndex);
        return keyStr.getBytes(StandardCharsets.UTF_8);
    }

    public void reset() {
        sequentialCounter.set(0);
        random.setSeed(config.getSeedValue());
    }

    public static KeyGenerator create(KeyspaceConfig config) {
        return new KeyGenerator(config);
    }
}
