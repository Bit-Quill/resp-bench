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

    public KeyGenerator(KeyspaceConfig config) {
        this.config = config;
        this.keyPrefix = config.getEffectiveKeyPrefix();
        this.keySizeBytes = config.getKeySizeBytes();
        this.keysCount = config.getKeysCount();
        this.sequentialCounter = new AtomicLong(0);
        this.random = new Random(config.getSeedValue());
    }

    public byte[] nextKey() {
        long keyIndex = config.isSequentialInt() 
                ? sequentialCounter.getAndIncrement() % keysCount
                : random.nextInt(keysCount);
        
        String keyStr = keyPrefix + String.format("%0" + Math.max(1, keySizeBytes - keyPrefix.length()) + "d", keyIndex);
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