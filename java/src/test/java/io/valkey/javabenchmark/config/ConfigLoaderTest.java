/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadDriverConfig() throws Exception {
        String json = """
            {
                "schema_version": "1.0",
                "driver_id": "jedis",
                "mode": "standalone",
                "description": "Test"
            }
            """;
        
        Path configPath = tempDir.resolve("driver.json");
        Files.writeString(configPath, json);
        
        DriverConfig config = ConfigLoader.loadDriverConfig(configPath);
        
        assertThat(config.getDriverId()).isEqualTo("jedis");
        assertThat(config.getMode()).isEqualTo("standalone");
        assertThat(config.isClusterMode()).isFalse();
    }

    @Test
    void shouldLoadWorkloadConfig() throws Exception {
        String json = """
            {
                "schema_version": "1.0",
                "benchmark_profile": {"name": "Test", "description": "Test", "version": "1.0"},
                "phases": [{
                    "id": "PHASE1",
                    "connections": 10,
                    "completion": {"type": "duration", "seconds": 10},
                    "keyspace": {"keys_count": 1000, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 100}]
                }]
            }
            """;
        
        Path configPath = tempDir.resolve("workload.json");
        Files.writeString(configPath, json);
        
        WorkloadConfig config = ConfigLoader.loadWorkloadConfig(configPath);
        
        assertThat(config.getPhases()).hasSize(1);
        assertThat(config.getPhases().get(0).getId()).isEqualTo("PHASE1");
        assertThat(config.getPhases().get(0).getConnections()).isEqualTo(10);
    }

    @Test
    void shouldDetectClusterMode() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "jedis", "mode": "cluster"}
            """);
        
        assertThat(config.isClusterMode()).isTrue();
    }

    @Test
    void shouldDetectTlsEnabled() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "jedis", "mode": "standalone", "tls": {}}
            """);
        
        assertThat(config.isTlsEnabled()).isTrue();
    }

    @Test
    void shouldThrowOnMissingDriverId() {
        assertThatThrownBy(() -> ConfigLoader.parseDriverConfig("{}"))
                .isInstanceOf(ConfigLoader.ConfigurationException.class);
    }
}