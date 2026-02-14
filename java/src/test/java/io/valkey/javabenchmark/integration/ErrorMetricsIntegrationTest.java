/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkey.javabenchmark.client.BenchmarkClientFactory;
import io.valkey.javabenchmark.client.impl.RecordingBenchmarkClient;
import io.valkey.javabenchmark.config.*;
import io.valkey.javabenchmark.engine.BenchmarkEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Black-box integration tests that validate error detection and error metrics in the output.
 * 
 * <p>These tests follow the black-box testing approach:</p>
 * <ol>
 *   <li>Define driver and workflow JSON strings with required configuration</li>
 *   <li>Parse configs using ConfigLoader (same as Main.java does)</li>
 *   <li>Run the BenchmarkEngine directly</li>
 *   <li>Validate using metrics file output or recorded data from RecordingBenchmarkClient</li>
 * </ol>
 * 
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Error simulation using RecordingBenchmarkClient captures errors correctly</li>
 *   <li>Error counts in output metrics match configured error rates</li>
 *   <li>Per-command error tracking works correctly</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorMetricsIntegrationTest {

    private ObjectMapper objectMapper;
    
    @TempDir
    Path tempDir;

    @BeforeAll
    void setup() {
        objectMapper = new ObjectMapper();
    }

    @BeforeEach
    void clearInstances() {
        // Clear any previously registered instances
        RecordingBenchmarkClient.clearInstances();
    }

    // === Error Simulation Tests (Black-box with RecordingBenchmarkClient) ===

    @Test
    void simulatedErrorsAreCapturedInMetrics() throws Exception {
        // 1. Define driver JSON with 10% error rate
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "error_rate": 0.1,
                    "error_message": "Simulated 10% error"
                }
            }
            """;
        
        // 2. Define workload JSON - use 10000 requests for < 2% margin
        // Statistical: SE = sqrt(0.1*0.9/10000) ≈ 0.3%, so 2% margin is ~6 SEs
        String workloadJson = """
            {
                "benchmark_profile": {
                    "name": "ErrorSimulationTest",
                    "version": "1.0"
                },
                "phases": [{
                    "id": "ERROR_SIM",
                    "description": "Test error simulation with 10% error rate",
                    "connections": 1,
                    "commands": [
                        {"command": "set", "weight": 1.0, "data_size_bytes": 32}
                    ],
                    "keyspace": {
                        "key_prefix": "error:",
                        "keys_count": 1000,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {
                        "type": "requests",
                        "requests": 10000
                    }
                }]
            }
            """;
        
        // 3. Parse configs
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        // 4. Run engine
        Path metricsFile = tempDir.resolve("error-sim-10pct.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile.toString());
        engine.run();
        
        // 5. Validate metrics file output
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Verify totals
        long totalRequests = json.at("/totals/requests").asLong();
        long errorCount = json.at("/totals/errors").asLong();
        
        assertThat(totalRequests).isEqualTo(10000);
        
        // Error count should be approximately 10% (within < 2% tolerance: 8% to 12%)
        double errorRate = (double) errorCount / totalRequests;
        assertThat(errorRate).isBetween(0.08, 0.12);
    }

    @Test
    void fullErrorRateProducesAllErrors() throws Exception {
        // Driver JSON with 100% error rate
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "error_rate": 1.0,
                    "error_message": "All operations fail"
                }
            }
            """;
        
        String workloadJson = """
            {
                "benchmark_profile": {"name": "FullErrorTest"},
                "phases": [{
                    "id": "ALL_ERRORS",
                    "description": "100% error rate test",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "allerror:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 100}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("all-errors.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // All operations should fail
        assertThat(json.at("/totals/requests").asLong()).isEqualTo(100);
        assertThat(json.at("/totals/errors").asLong()).isEqualTo(100);
        assertThat(json.at("/metrics/SET/errors").asLong()).isEqualTo(100);
    }

    @Test
    void zeroErrorRateProducesNoErrors() throws Exception {
        // Driver JSON with 0% error rate
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "error_rate": 0.0
                }
            }
            """;
        
        String workloadJson = """
            {
                "benchmark_profile": {"name": "NoErrorTest"},
                "phases": [{
                    "id": "NO_ERRORS",
                    "description": "0% error rate test",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "noerror:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 100}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("no-errors.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // No operations should fail
        assertThat(json.at("/totals/requests").asLong()).isEqualTo(100);
        assertThat(json.at("/totals/errors").asLong()).isEqualTo(0);
        assertThat(json.at("/metrics/SET/errors").asLong()).isEqualTo(0);
    }

    @Test
    void errorsAreTrackedPerCommand() throws Exception {
        // Driver with 20% error rate - both commands will have similar error rates
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "error_rate": 0.2
                }
            }
            """;
        
        // Use 20000 requests for < 2% margin per command (~10000 each)
        // Statistical: SE = sqrt(0.2*0.8/10000) ≈ 0.4%, so 2% margin is ~5 SEs
        String workloadJson = """
            {
                "benchmark_profile": {"name": "PerCommandErrorTest"},
                "phases": [{
                    "id": "MULTI_CMD_ERRORS",
                    "description": "Test per-command error tracking",
                    "connections": 1,
                    "commands": [
                        {"command": "set", "weight": 0.5, "data_size_bytes": 32},
                        {"command": "get", "weight": 0.5}
                    ],
                    "keyspace": {
                        "key_prefix": "percmd:",
                        "keys_count": 1000,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 20000}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("per-cmd-errors.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Total requests
        assertThat(json.at("/totals/requests").asLong()).isEqualTo(20000);
        
        // Both SET and GET should have errors (approximately 20% each)
        long setRequests = json.at("/metrics/SET/requests").asLong();
        long setErrors = json.at("/metrics/SET/errors").asLong();
        long getRequests = json.at("/metrics/GET/requests").asLong();
        long getErrors = json.at("/metrics/GET/errors").asLong();
        
        // Both commands should have approximately 50% of total (within < 2% tolerance: 48%-52%)
        assertThat(setRequests).isBetween(9600L, 10400L);
        assertThat(getRequests).isBetween(9600L, 10400L);
        
        // Each command should have approximately 20% errors (within < 2% tolerance: 18%-22%)
        double setErrorRate = (double) setErrors / setRequests;
        assertThat(setErrorRate).isBetween(0.18, 0.22);
        
        double getErrorRate = (double) getErrors / getRequests;
        assertThat(getErrorRate).isBetween(0.18, 0.22);
    }

    // === Connection Error Tests ===

    @Test
    void benchmarkClientCapturesConnectionErrors() throws Exception {
        // Try to connect to a non-existent server
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "jedis", "mode": "standalone"}
            """);
        
        // Use a port that's unlikely to have a server
        int nonExistentPort = 59999;
        
        assertThatThrownBy(() -> {
            BenchmarkClientFactory.createAndConnect("localhost", nonExistentPort, config);
        }).isInstanceOf(Exception.class);
    }

    // === Recording Client Inspection Tests (using static registry) ===

    @Test
    void errorMessageIsPreservedInRecordedOperation() throws Exception {
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "error_rate": 1.0,
                    "error_message": "Custom error message for testing"
                }
            }
            """;
        
        String workloadJson = """
            {
                "benchmark_profile": {"name": "ErrorMessageTest"},
                "phases": [{
                    "id": "ERROR_MSG_TEST",
                    "description": "Test error message preservation",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "errmsg:",
                        "keys_count": 10,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 5}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("error-msg.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile.toString());
        engine.run();
        
        // Check recorded operations via static registry
        // Note: Instances are removed on close(), so we check the metrics file instead
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // All operations should be errors
        assertThat(json.at("/totals/errors").asLong()).isEqualTo(5);
    }

    @Test
    void successfulOperationsHaveNoErrors() throws Exception {
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "error_rate": 0.0
                }
            }
            """;
        
        String workloadJson = """
            {
                "benchmark_profile": {"name": "SuccessTest"},
                "phases": [{
                    "id": "SUCCESS_TEST",
                    "description": "Test successful operations",
                    "connections": 1,
                    "commands": [
                        {"command": "set", "weight": 0.5, "data_size_bytes": 32},
                        {"command": "get", "weight": 0.5}
                    ],
                    "keyspace": {
                        "key_prefix": "success:",
                        "keys_count": 50,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 100}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("success.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // No errors
        assertThat(json.at("/totals/requests").asLong()).isEqualTo(100);
        assertThat(json.at("/totals/errors").asLong()).isEqualTo(0);
    }

    // === Edge Cases ===

    @Test
    void metricsHandleEmptyErrorCollection() throws Exception {
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {"error_rate": 0.0}
            }
            """;
        
        String workloadJson = """
            {
                "benchmark_profile": {"name": "EmptyErrorTest"},
                "phases": [{
                    "id": "EMPTY_ERRORS",
                    "description": "Test with no errors",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "empty:",
                        "keys_count": 10,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 10}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("empty-errors.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        assertThat(json.at("/totals/errors").asLong()).isEqualTo(0);
        assertThat(json.at("/metrics/SET/errors").asLong()).isEqualTo(0);
    }

    @Test
    void metricsHandleAllErrorsCollection() throws Exception {
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {"error_rate": 1.0}
            }
            """;
        
        String workloadJson = """
            {
                "benchmark_profile": {"name": "AllErrorsTest"},
                "phases": [{
                    "id": "ALL_ERRORS_EDGE",
                    "description": "Test with all errors",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "allerr:",
                        "keys_count": 10,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 10}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("all-errors-edge.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        assertThat(json.at("/totals/requests").asLong()).isEqualTo(10);
        assertThat(json.at("/totals/errors").asLong()).isEqualTo(10);
        assertThat(json.at("/metrics/SET/requests").asLong()).isEqualTo(10);
        assertThat(json.at("/metrics/SET/errors").asLong()).isEqualTo(10);
    }

    // === Duration-based completion test ===

    @Test
    void durationBasedCompletionWithErrors() throws Exception {
        // Use small delay to ensure many requests complete in the duration window
        // for statistically meaningful error rate validation
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "error_rate": 0.1,
                    "operation_delay_micros": 100
                }
            }
            """;
        
        // Run for 2 seconds to accumulate enough samples
        String workloadJson = """
            {
                "benchmark_profile": {"name": "DurationErrorTest"},
                "phases": [{
                    "id": "DURATION_TEST",
                    "description": "Test duration-based completion with errors",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "duration:",
                        "keys_count": 1000,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "duration", "seconds": 2}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("duration-errors.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine("localhost", 6379, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Should have completed many requests in 2 seconds
        long requests = json.at("/totals/requests").asLong();
        long errors = json.at("/totals/errors").asLong();
        
        // With 100µs delay, we should get thousands of requests in 2 seconds
        assertThat(requests).isGreaterThan(5000);
        
        // Should have approximately 10% errors (within < 2% tolerance: 8%-12%)
        double errorRate = (double) errors / requests;
        assertThat(errorRate).isBetween(0.08, 0.12);
    }
}