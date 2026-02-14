/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.valkey.javabenchmark.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration model for keyspace settings.
 *
 * @author Ilia Kolominsky
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeyspaceConfig {

    public static final String ALG_SEQUENTIAL_INT = "sequential_int";
    public static final String ALG_UNIFORM_RAND = "uniform_rand";

    @JsonProperty("seed")
    private Long seed;

    @JsonProperty("keys_count")
    private int keysCount;

    @JsonProperty("key_size_bytes")
    private int keySizeBytes = 16;

    @JsonProperty("key_prefix")
    private String keyPrefix = "";

    @JsonProperty("generation_alg")
    private String generationAlg;

    // Getters and Setters

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public int getKeysCount() {
        return keysCount;
    }

    public void setKeysCount(int keysCount) {
        this.keysCount = keysCount;
    }

    public int getKeySizeBytes() {
        return keySizeBytes;
    }

    public void setKeySizeBytes(int keySizeBytes) {
        this.keySizeBytes = keySizeBytes;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getGenerationAlg() {
        return generationAlg;
    }

    public void setGenerationAlg(String generationAlg) {
        this.generationAlg = generationAlg;
    }

    // Convenience methods

    /**
     * Check if using sequential integer key generation.
     * 
     * @return true if algorithm is "sequential_int"
     */
    public boolean isSequentialInt() {
        return ALG_SEQUENTIAL_INT.equalsIgnoreCase(generationAlg);
    }

    /**
     * Check if using uniform random key generation.
     * 
     * @return true if algorithm is "uniform_rand"
     */
    public boolean isUniformRand() {
        return ALG_UNIFORM_RAND.equalsIgnoreCase(generationAlg);
    }

    /**
     * Get the seed value, defaulting to 0 if not set.
     * 
     * @return seed value
     */
    public long getSeedValue() {
        return seed != null ? seed : 0L;
    }

    /**
     * Get the effective key prefix (never null).
     * 
     * @return key prefix or empty string
     */
    public String getEffectiveKeyPrefix() {
        return keyPrefix != null ? keyPrefix : "";
    }

    /**
     * Validate the keyspace configuration.
     * 
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (keysCount <= 0) {
            throw new IllegalArgumentException("Keys count must be positive");
        }
        if (keySizeBytes <= 0) {
            throw new IllegalArgumentException("Key size bytes must be positive");
        }
        if (generationAlg == null || generationAlg.trim().isEmpty()) {
            throw new IllegalArgumentException("Generation algorithm is required");
        }
        if (!isSequentialInt() && !isUniformRand()) {
            throw new IllegalArgumentException(
                    "Unknown generation algorithm: " + generationAlg +
                    ". Supported: " + ALG_SEQUENTIAL_INT + ", " + ALG_UNIFORM_RAND);
        }
        // For sequential_int, seed should not be configured
        if (isSequentialInt() && seed != null) {
            // Just warn, don't fail - seed will be ignored
        }
    }

    @Override
    public String toString() {
        return "KeyspaceConfig{" +
                "keysCount=" + keysCount +
                ", keySizeBytes=" + keySizeBytes +
                ", keyPrefix='" + keyPrefix + '\'' +
                ", generationAlg='" + generationAlg + '\'' +
                (seed != null ? ", seed=" + seed : "") +
                '}';
    }
}