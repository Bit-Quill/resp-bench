/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.engine;

import io.valkey.javabenchmark.config.KeyspaceConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class KeyGeneratorTest {

    private KeyspaceConfig createConfig(int keysCount, int keySizeBytes, String keyPrefix, String generationAlg, Long seed) {
        KeyspaceConfig config = new KeyspaceConfig();
        config.setKeysCount(keysCount);
        config.setKeySizeBytes(keySizeBytes);
        config.setKeyPrefix(keyPrefix);
        config.setGenerationAlg(generationAlg);
        if (seed != null) {
            config.setSeed(seed);
        }
        return config;
    }

    @Test
    void shouldGenerateSequentialKeys() {
        KeyspaceConfig config = createConfig(100, 16, "test:", "sequential_int", null);
        
        KeyGenerator generator = KeyGenerator.create(config);
        
        byte[] key1 = generator.nextKey();
        byte[] key2 = generator.nextKey();
        
        assertThat(new String(key1)).startsWith("test:");
        assertThat(new String(key2)).startsWith("test:");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void shouldGenerateUniformRandomKeys() {
        KeyspaceConfig config = createConfig(1000, 16, "rand:", "uniform_rand", 12345L);
        
        KeyGenerator generator = KeyGenerator.create(config);
        
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            keys.add(new String(generator.nextKey()));
        }
        
        // Should have multiple unique keys (randomness)
        assertThat(keys.size()).isGreaterThan(50);
    }

    @Test
    void shouldResetSequentialCounter() {
        KeyspaceConfig config = createConfig(100, 16, "test:", "sequential_int", null);
        
        KeyGenerator generator = KeyGenerator.create(config);
        
        byte[] first1 = generator.nextKey();
        generator.nextKey();
        generator.reset();
        byte[] first2 = generator.nextKey();
        
        assertThat(first1).isEqualTo(first2);
    }
}