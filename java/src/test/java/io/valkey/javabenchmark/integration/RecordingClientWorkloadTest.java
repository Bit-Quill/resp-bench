/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.integration;

import io.valkey.javabenchmark.client.impl.RecordingBenchmarkClient;
import io.valkey.javabenchmark.client.impl.RecordingBenchmarkClient.RecordedOperation;
import io.valkey.javabenchmark.config.*;
import io.valkey.javabenchmark.engine.BenchmarkEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * Black-box integration tests that validate workload configuration settings using RecordingBenchmarkClient.
 * 
 * <p>These tests follow the black-box testing approach:</p>
 * <ol>
 *   <li>Define driver and workload JSON strings with required configuration</li>
 *   <li>Parse configs using ConfigLoader (same as Main.java does)</li>
 *   <li>Run the BenchmarkEngine directly with recording client</li>
 *   <li>Validate using static aggregate operations from RecordingBenchmarkClient</li>
 * </ol>
 * 
 * <p>The RecordingBenchmarkClient records all operations to a static aggregate collection
 * that survives instance close, allowing tests to validate key prefixes, key distribution,
 * data sizes, and key generation algorithms after the benchmark completes.</p>
 * 
 * <p>Tests validate:</p>
 * <ul>
 *   <li>Key prefix is correctly applied to all generated keys</li>
 *   <li>Key numbers are within the configured keys_count range</li>
 *   <li>Sequential key generation produces keys in order and wraps correctly</li>
 *   <li>Uniform random key generation produces reasonable distribution</li>
 *   <li>Data size bytes produces values of correct size</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordingClientWorkloadTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6379;

    @TempDir
    Path tempDir;

    @BeforeEach
    void clearInstances() {
        RecordingBenchmarkClient.clearInstances();
    }

    // === Recording Driver Configuration ===

    private static String recordingDriverJson() {
        return """
            {"driver_id": "recording", "mode": "standalone"}
            """;
    }

    // === Key Prefix Tests ===

    @Test
    void keyPrefixIsAppliedToAllKeys() throws Exception {
        String workloadJson = """
            {
                "benchmark_profile": {"name": "KeyPrefixTest"},
                "phases": [{
                    "id": "KEY_PREFIX",
                    "description": "Test key prefix is applied",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 64}],
                    "keyspace": {
                        "key_prefix": "myprefix:",
                        "keys_count": 100,
                        "key_size_bytes": 20,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 100}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("key-prefix.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via aggregate operations
        List<RecordedOperation> setOps = RecordingBenchmarkClient.getAggregateOperations("SET");
        assertThat(setOps).hasSize(100);
        
        for (RecordedOperation op : setOps) {
            String key = new String(op.key());
            assertThat(key).startsWith("myprefix:");
        }
    }

    @Test
    void emptyKeyPrefixProducesKeysWithoutPrefix() throws Exception {
        String workloadJson = """
            {
                "benchmark_profile": {"name": "EmptyPrefixTest"},
                "phases": [{
                    "id": "EMPTY_PREFIX",
                    "description": "Test empty key prefix",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "",
                        "keys_count": 50,
                        "key_size_bytes": 10,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 50}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("empty-prefix.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via aggregate operations
        List<RecordedOperation> setOps = RecordingBenchmarkClient.getAggregateOperations("SET");
        assertThat(setOps).hasSize(50);
        
        // Keys should be numeric only (padded)
        for (RecordedOperation op : setOps) {
            String key = new String(op.key());
            assertThat(key).matches("\\d+");
        }
    }

    // === Key Range Tests ===

    @Test
    void keyNumbersAreWithinKeysCountRange() throws Exception {
        int keysCount = 100;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "KeyRangeTest"},
                "phases": [{
                    "id": "KEY_RANGE",
                    "description": "Test key numbers are within range",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "test:",
                        "keys_count": %d,
                        "key_size_bytes": 16,
                        "generation_alg": "uniform_rand",
                        "seed": 42
                    },
                    "completion": {"type": "requests", "requests": 500}
                }]
            }
            """, keysCount);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("key-range.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via aggregate operations
        List<RecordedOperation> setOps = RecordingBenchmarkClient.getAggregateOperations("SET");
        assertThat(setOps).hasSize(500);
        
        Pattern keyPattern = Pattern.compile("test:(\\d+)");
        
        for (RecordedOperation op : setOps) {
            String key = new String(op.key());
            Matcher matcher = keyPattern.matcher(key);
            assertThat(matcher.find()).isTrue();
            
            int keyIndex = Integer.parseInt(matcher.group(1));
            assertThat(keyIndex).isBetween(0, keysCount - 1);
        }
    }

    // === Sequential Key Generation Tests ===

    @Test
    void sequentialIntGeneratesSequentialKeys() throws Exception {
        String workloadJson = """
            {
                "benchmark_profile": {"name": "SequentialKeysTest"},
                "phases": [{
                    "id": "SEQUENTIAL_KEYS",
                    "description": "Test sequential key generation",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "seq:",
                        "keys_count": 1000,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 10}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("sequential-keys.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via aggregate operations
        List<RecordedOperation> setOps = RecordingBenchmarkClient.getAggregateOperations("SET");
        assertThat(setOps).hasSize(10);
        
        // Extract key indices and verify they are sequential
        List<Integer> keyIndices = new ArrayList<>();
        Pattern keyPattern = Pattern.compile("seq:(\\d+)");
        
        for (RecordedOperation op : setOps) {
            String key = new String(op.key());
            Matcher matcher = keyPattern.matcher(key);
            assertThat(matcher.find()).isTrue();
            keyIndices.add(Integer.parseInt(matcher.group(1)));
        }
        
        // Verify sequential order: 0, 1, 2, 3, ...
        for (int i = 0; i < keyIndices.size(); i++) {
            assertThat(keyIndices.get(i)).isEqualTo(i);
        }
    }

    @Test
    void sequentialIntWrapsAroundAtKeysCount() throws Exception {
        int keysCount = 5;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "WrapAroundTest"},
                "phases": [{
                    "id": "WRAP_AROUND",
                    "description": "Test key wrap-around at keys_count",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "wrap:",
                        "keys_count": %d,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 12}
                }]
            }
            """, keysCount);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("wrap-around.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via aggregate operations
        List<RecordedOperation> setOps = RecordingBenchmarkClient.getAggregateOperations("SET");
        assertThat(setOps).hasSize(12);
        
        // Extract key indices
        List<Integer> keyIndices = new ArrayList<>();
        Pattern keyPattern = Pattern.compile("wrap:(\\d+)");
        
        for (RecordedOperation op : setOps) {
            String key = new String(op.key());
            Matcher matcher = keyPattern.matcher(key);
            assertThat(matcher.find()).isTrue();
            keyIndices.add(Integer.parseInt(matcher.group(1)));
        }
        
        // Expected: 0, 1, 2, 3, 4, 0, 1, 2, 3, 4, 0, 1
        int[] expected = {0, 1, 2, 3, 4, 0, 1, 2, 3, 4, 0, 1};
        for (int i = 0; i < expected.length; i++) {
            assertThat(keyIndices.get(i)).isEqualTo(expected[i]);
        }
    }

    // === Uniform Random Key Generation Tests ===

    @Test
    void uniformRandGeneratesRandomDistribution() throws Exception {
        int keysCount = 100;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "RandomDistributionTest"},
                "phases": [{
                    "id": "RANDOM_DIST",
                    "description": "Test uniform random key distribution",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "rand:",
                        "keys_count": %d,
                        "key_size_bytes": 16,
                        "generation_alg": "uniform_rand",
                        "seed": 12345
                    },
                    "completion": {"type": "requests", "requests": 1000}
                }]
            }
            """, keysCount);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("random-dist.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via aggregate operations
        List<RecordedOperation> setOps = RecordingBenchmarkClient.getAggregateOperations("SET");
        assertThat(setOps).hasSize(1000);
        
        // Extract key indices and count distribution
        Map<Integer, Integer> keyCounts = new HashMap<>();
        Pattern keyPattern = Pattern.compile("rand:(\\d+)");
        
        for (RecordedOperation op : setOps) {
            String key = new String(op.key());
            Matcher matcher = keyPattern.matcher(key);
            assertThat(matcher.find()).isTrue();
            int keyIndex = Integer.parseInt(matcher.group(1));
            keyCounts.merge(keyIndex, 1, Integer::sum);
        }
        
        // With 1000 ops and 100 keys, each key should be hit ~10 times on average
        // Verify that keys are distributed (not all same key)
        assertThat(keyCounts.size()).isGreaterThan(50); // At least 50 unique keys hit
        
        // Verify no single key has more than 5% of all operations (reasonable randomness)
        int maxCount = keyCounts.values().stream().max(Integer::compare).orElse(0);
        assertThat(maxCount).isLessThan(50); // No key should have > 5% of ops
    }

    // === Data Size Tests ===

    @Test
    void dataSizeBytesProducesCorrectValueSize() throws Exception {
        int expectedDataSize = 128;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "DataSizeTest"},
                "phases": [{
                    "id": "DATA_SIZE",
                    "description": "Test data size bytes",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": %d}],
                    "keyspace": {
                        "key_prefix": "data:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 20}
                }]
            }
            """, expectedDataSize);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("data-size.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via aggregate operations
        List<RecordedOperation> setOps = RecordingBenchmarkClient.getAggregateSetOperationsWithValues();
        assertThat(setOps).hasSize(20);
        
        // Verify all values have correct size
        for (RecordedOperation op : setOps) {
            assertThat(op.value()).hasSize(expectedDataSize);
        }
    }

    @Test
    void differentDataSizesAreRespected() throws Exception {
        // Test multiple sizes in separate phases
        String workloadJson = """
            {
                "benchmark_profile": {"name": "MultiDataSizeTest"},
                "phases": [
                    {
                        "id": "SIZE_32",
                        "description": "Test 32-byte values",
                        "connections": 1,
                        "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                        "keyspace": {"key_prefix": "s32:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                        "completion": {"type": "requests", "requests": 5}
                    },
                    {
                        "id": "SIZE_256",
                        "description": "Test 256-byte values",
                        "connections": 1,
                        "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 256}],
                        "keyspace": {"key_prefix": "s256:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                        "completion": {"type": "requests", "requests": 5}
                    },
                    {
                        "id": "SIZE_1024",
                        "description": "Test 1024-byte values",
                        "connections": 1,
                        "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 1024}],
                        "keyspace": {"key_prefix": "s1024:", "keys_count": 100, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                        "completion": {"type": "requests", "requests": 5}
                    }
                ]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("multi-data-size.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via aggregate operations
        List<RecordedOperation> allSetOps = RecordingBenchmarkClient.getAggregateSetOperationsWithValues();
        assertThat(allSetOps).hasSize(15); // 5 + 5 + 5
        
        // Group by prefix and verify sizes
        int count32 = 0, count256 = 0, count1024 = 0;
        
        for (RecordedOperation op : allSetOps) {
            String key = new String(op.key());
            if (key.startsWith("s32:")) {
                assertThat(op.value()).as("32-byte value size").hasSize(32);
                count32++;
            } else if (key.startsWith("s256:")) {
                assertThat(op.value()).as("256-byte value size").hasSize(256);
                count256++;
            } else if (key.startsWith("s1024:")) {
                assertThat(op.value()).as("1024-byte value size").hasSize(1024);
                count1024++;
            }
        }
        
        assertThat(count32).isEqualTo(5);
        assertThat(count256).isEqualTo(5);
        assertThat(count1024).isEqualTo(5);
    }
}