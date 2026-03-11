/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkey.javabenchmark.client.impl.RecordingBenchmarkClient;
import io.valkey.javabenchmark.config.*;
import io.valkey.javabenchmark.engine.BenchmarkEngine;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Black-box integration tests that validate NDJSON metrics output format and histogram accuracy.
 * 
 * <p>These tests follow the black-box testing approach:</p>
 * <ol>
 *   <li>Define driver and workload JSON strings with required configuration</li>
 *   <li>Parse configs using ConfigLoader (same as Main.java does)</li>
 *   <li>Run the BenchmarkEngine directly</li>
 *   <li>Validate using metrics file output (NDJSON)</li>
 * </ol>
 * 
 * <p>Most tests are parameterized to run with all 9 supported drivers:</p>
 * <ul>
 *   <li>4 core drivers: jedis, lettuce, valkey-glide, redisson</li>
 *   <li>2 Spring Data Redis drivers: jedis, lettuce</li>
 *   <li>3 Spring Data Valkey drivers: jedis, lettuce, valkey-glide</li>
 * </ul>
 * 
 * <p>Additionally, the {@code latencyHistogramWithLongTailDistribution} test uses the 
 * recording client to validate histogram accuracy with a known log-normal distribution.</p>
 * 
 * <p>Server endpoint can be configured via system properties:</p>
 * <ul>
 *   <li>valkey.host (default: localhost)</li>
 *   <li>valkey.port (default: 6379)</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricsOutputTest {

    private static String host;
    private static int port;
    private ObjectMapper objectMapper;
    
    @TempDir
    Path tempDir;

    @BeforeAll
    void setup() {
        objectMapper = new ObjectMapper();
        
        // Read server configuration from system properties
        host = System.getProperty("valkey.host", "localhost");
        if (host.isEmpty()) {
            host = "localhost";
        }
        String portStr = System.getProperty("valkey.port", "6379");
        port = Integer.parseInt(portStr.isEmpty() ? "6379" : portStr);
    }

    @BeforeEach
    void clearInstances() {
        RecordingBenchmarkClient.clearInstances();
    }

    // === Driver Configurations for Parameterized Tests ===

    /**
     * Provides all 9 driver configurations for parameterized tests:
     * - 4 core drivers: jedis, lettuce, valkey-glide, redisson
     * - 2 Spring Data Redis drivers: jedis, lettuce
     * - 3 Spring Data Valkey drivers: jedis, lettuce, valkey-glide
     */
    static Stream<Arguments> allDrivers() {
        return Stream.of(
            // Core drivers
            Arguments.of("jedis", """
                {"driver_id": "jedis", "mode": "standalone"}
                """),
            Arguments.of("lettuce", """
                {"driver_id": "lettuce", "mode": "standalone"}
                """),
            Arguments.of("valkey-glide", """
                {"driver_id": "valkey-glide", "mode": "standalone"}
                """),
            Arguments.of("redisson", """
                {"driver_id": "redisson", "mode": "standalone"}
                """),
            // Spring Data Redis drivers
            Arguments.of("spring-data-redis-jedis", """
                {"driver_id": "spring-data-redis", "secondary_driver_id": "jedis", "mode": "standalone"}
                """),
            Arguments.of("spring-data-redis-lettuce", """
                {"driver_id": "spring-data-redis", "secondary_driver_id": "lettuce", "mode": "standalone"}
                """),
            // Spring Data Valkey drivers
            Arguments.of("spring-data-valkey-jedis", """
                {"driver_id": "spring-data-valkey", "secondary_driver_id": "jedis", "mode": "standalone"}
                """),
            Arguments.of("spring-data-valkey-lettuce", """
                {"driver_id": "spring-data-valkey", "secondary_driver_id": "lettuce", "mode": "standalone"}
                """),
            Arguments.of("spring-data-valkey-glide", """
                {"driver_id": "spring-data-valkey", "secondary_driver_id": "valkey-glide", "mode": "standalone"}
                """)
        );
    }

    // === NDJSON Format Tests ===

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDrivers")
    void ndjsonOutputHasValidFormat(String driverName, String driverJson) throws Exception {
        String workloadJson = """
            {
                "benchmark_profile": {"name": "FormatTest"},
                "phases": [{
                    "id": "FORMAT_TEST",
                    "description": "Test NDJSON format",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "fmt:",
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
        
        Path metricsFile = tempDir.resolve("format-" + driverName + ".ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString());
        engine.run();
        
        // Verify file exists and has valid JSON
        assertThat(metricsFile).exists();
        
        String line = Files.readString(metricsFile).trim();
        assertThat(line).isNotEmpty();
        
        // Parse JSON (will throw if invalid)
        JsonNode json = objectMapper.readTree(line);
        
        // Verify it's a single line (NDJSON)
        assertThat(line.split("\n")).hasSize(1);
        assertThat(json.isObject()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDrivers")
    void multiplePhasesProduceMultipleLines(String driverName, String driverJson) throws Exception {
        String workloadJson = """
            {
                "benchmark_profile": {"name": "MultiPhaseTest"},
                "phases": [
                    {
                        "id": "PHASE_1",
                        "description": "Phase 1",
                        "connections": 1,
                        "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                        "keyspace": {"key_prefix": "p1:", "keys_count": 50, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                        "completion": {"type": "requests", "requests": 50}
                    },
                    {
                        "id": "PHASE_2",
                        "description": "Phase 2",
                        "connections": 1,
                        "commands": [{"command": "get", "weight": 1.0}],
                        "keyspace": {"key_prefix": "p1:", "keys_count": 50, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                        "completion": {"type": "requests", "requests": 50}
                    },
                    {
                        "id": "PHASE_3",
                        "description": "Phase 3",
                        "connections": 1,
                        "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                        "keyspace": {"key_prefix": "p3:", "keys_count": 50, "key_size_bytes": 16, "generation_alg": "sequential_int"},
                        "completion": {"type": "requests", "requests": 50}
                    }
                ]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("multi-phase-" + driverName + ".ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString());
        engine.run();
        
        // Verify three lines
        List<String> lines = Files.readAllLines(metricsFile);
        assertThat(lines).hasSize(3);
        
        // Each line should be valid JSON with correct phase ID
        for (int i = 0; i < lines.size(); i++) {
            JsonNode json = objectMapper.readTree(lines.get(i));
            assertThat(json.at("/phase/id").asText()).isEqualTo("PHASE_" + (i + 1));
        }
    }

    // === Phase Metadata Tests ===

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDrivers")
    void phaseMetadataIsCorrect(String driverName, String driverJson) throws Exception {
        String workloadJson = """
            {
                "benchmark_profile": {"name": "MetadataTest"},
                "phases": [{
                    "id": "METADATA_TEST",
                    "description": "Test phase metadata",
                    "connections": 2,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "meta:",
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
        
        Path metricsFile = tempDir.resolve("metadata-" + driverName + ".ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Verify phase metadata
        assertThat(json.at("/phase/id").asText()).isEqualTo("METADATA_TEST");
        assertThat(json.at("/phase/status").asText()).isEqualTo("COMPLETED");
        assertThat(json.at("/phase/connections").asInt()).isEqualTo(2);
        assertThat(json.at("/phase/duration_ms").asLong()).isGreaterThan(0);
        assertThat(json.at("/phase/start_timestamp").asText()).isNotEmpty();
        assertThat(json.at("/phase/finish_timestamp").asText()).isNotEmpty();
    }

    // === Request Count Tests ===

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDrivers")
    void totalRequestCountMatchesExecution(String driverName, String driverJson) throws Exception {
        int targetRequests = 1000;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "RequestCountTest"},
                "phases": [{
                    "id": "COUNT_TEST",
                    "description": "Test request count",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "count:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, targetRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("count-" + driverName + ".ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        assertThat(json.at("/totals/requests").asLong()).isEqualTo(targetRequests);
        assertThat(json.at("/metrics/SET/requests").asLong()).isEqualTo(targetRequests);
    }

    // === Histogram Tests ===

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDrivers")
    void histogramCapturesLatency(String driverName, String driverJson) throws Exception {
        int targetRequests = 1000;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "HistogramTest"},
                "phases": [{
                    "id": "HISTOGRAM_TEST",
                    "description": "Test histogram capture",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "hist:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, targetRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("histogram-" + driverName + ".ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Verify histogram summary values exist and are reasonable
        JsonNode summary = json.at("/metrics/SET/latency/summary");
        long min = summary.at("/min").asLong();
        long p50 = summary.at("/p50").asLong();
        long p95 = summary.at("/p95").asLong();
        long p99 = summary.at("/p99").asLong();
        long max = summary.at("/max").asLong();
        
        // Min should be > 0 and <= p50 <= p95 <= p99 <= max
        assertThat(min).isGreaterThan(0);
        assertThat(p50).isGreaterThanOrEqualTo(min);
        assertThat(p95).isGreaterThanOrEqualTo(p50);
        assertThat(p99).isGreaterThanOrEqualTo(p95);
        assertThat(max).isGreaterThanOrEqualTo(p99);
        
        // Get metrics for diagnostic output
        long actualCount = json.at("/metrics/SET/latency/count").asLong();
        long errors = json.at("/metrics/SET/errors").asLong();
        long requests = json.at("/metrics/SET/requests").asLong();
        
        // Diagnostic output
        // System.out.println("[" + driverName + "] requests=" + requests + 
        //                   ", latency_count=" + actualCount + 
        //                   ", errors=" + errors);
        
        // Assert NO errors - all SET commands should succeed
        assertThat(errors)
            .describedAs("Driver %s had %d errors, expected 0", driverName, errors)
            .isEqualTo(0);
        
        // Assert strict equality - latency count must equal request count (no tolerance)
        assertThat(actualCount)
            .describedAs("Driver %s latency count mismatch: expected %d but was %d", 
                        driverName, targetRequests, actualCount)
            .isEqualTo(targetRequests);
        
        assertThat(json.at("/metrics/SET/latency/unit").asText()).isEqualTo("us");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDrivers")
    void histogramBase64CanBeDecoded(String driverName, String driverJson) throws Exception {
        String workloadJson = """
            {
                "benchmark_profile": {"name": "DecodeTest"},
                "phases": [{
                    "id": "DECODE_TEST",
                    "description": "Test histogram decoding",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "decode:",
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
        
        Path metricsFile = tempDir.resolve("decode-" + driverName + ".ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Get base64 payload
        String base64Payload = json.at("/metrics/SET/latency/hdr/payload_b64").asText();
        assertThat(base64Payload).isNotEmpty();
        
        // Decode and verify
        byte[] decoded = Base64.getDecoder().decode(base64Payload);
        assertThat(decoded.length).isGreaterThan(0);
        
        // Decode the histogram
        ByteBuffer buffer = ByteBuffer.wrap(decoded);
        Histogram decodedHistogram = Histogram.decodeFromCompressedByteBuffer(buffer, 0);
        
        // Verify histogram properties
        assertThat(decodedHistogram.getTotalCount()).isEqualTo(100);
        assertThat(decodedHistogram.getMinValue()).isGreaterThan(0);
        assertThat(decodedHistogram.getMaxValue()).isGreaterThan(decodedHistogram.getMinValue());
    }

    // === Per-Command Metrics Tests ===

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDrivers")
    void perCommandMetricsAreAccurate(String driverName, String driverJson) throws Exception {
        int targetRequests = 10000;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "PerCommandTest"},
                "phases": [{
                    "id": "MULTI_CMD",
                    "description": "Test per-command metrics",
                    "connections": 10,
                    "commands": [
                        {"command": "set", "weight": 0.5, "data_size_bytes": 32},
                        {"command": "get", "weight": 0.5}
                    ],
                    "keyspace": {
                        "key_prefix": "percmd:",
                        "keys_count": 200,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, targetRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("percmd-" + driverName + ".ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Verify we have all expected requests
        long totalRequests = json.at("/totals/requests").asLong();
        assertThat(totalRequests).isEqualTo(targetRequests);
        
        // Both SET and GET should have some requests
        long setRequests = json.at("/metrics/SET/requests").asLong();
        long getRequests = json.at("/metrics/GET/requests").asLong();
        
        // Total should match sum of individual commands
        assertThat(setRequests + getRequests).isEqualTo(totalRequests);
        
        // Each command should have roughly 50%
        assertThat(setRequests).isCloseTo(5000, withinPercentage(3));
        assertThat(getRequests).isCloseTo(5000, withinPercentage(3));

        // Get error counts
        long setErrors = json.at("/metrics/SET/errors").asLong();
        long getErrors = json.at("/metrics/GET/errors").asLong();
        
        // Latency counts
        long setLatencyCount = json.at("/metrics/SET/latency/count").asLong();
        long getLatencyCount = json.at("/metrics/GET/latency/count").asLong();
        
        // Assert NO errors - all commands should succeed
        assertThat(setErrors)
            .describedAs("Driver %s SET had %d errors, expected 0", driverName, setErrors)
            .isEqualTo(0);
        assertThat(getErrors)
            .describedAs("Driver %s GET had %d errors, expected 0", driverName, getErrors)
            .isEqualTo(0);
        
        // Assert strict equality - latency counts must equal request counts
        assertThat(setLatencyCount)
            .describedAs("Driver %s SET latency count mismatch", driverName)
            .isEqualTo(setRequests);
        assertThat(getLatencyCount)
            .describedAs("Driver %s GET latency count mismatch", driverName)
            .isEqualTo(getRequests);
    }

    // === Schema Validation Tests ===

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDrivers")
    void outputContainsAllRequiredFields(String driverName, String driverJson) throws Exception {
        String workloadJson = """
            {
                "benchmark_profile": {"name": "SchemaTest"},
                "phases": [{
                    "id": "SCHEMA_TEST",
                    "description": "Test schema completeness",
                    "connections": 1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "schema:",
                        "keys_count": 10,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": 20}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("schema-" + driverName + ".ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Phase fields
        assertThat(json.has("phase")).describedAs("phase field missing").isTrue();
        assertThat(json.at("/phase").has("id")).describedAs("phase.id field missing").isTrue();
        assertThat(json.at("/phase").has("status")).describedAs("phase.status field missing").isTrue();
        assertThat(json.at("/phase").has("start_timestamp")).describedAs("phase.start_timestamp field missing").isTrue();
        assertThat(json.at("/phase").has("finish_timestamp")).describedAs("phase.finish_timestamp field missing").isTrue();
        assertThat(json.at("/phase").has("duration_ms")).describedAs("phase.duration_ms field missing").isTrue();
        assertThat(json.at("/phase").has("connections")).describedAs("phase.connections field missing").isTrue();
        
        // Totals fields
        assertThat(json.has("totals")).describedAs("totals field missing").isTrue();
        assertThat(json.at("/totals").has("requests")).describedAs("totals.requests field missing").isTrue();
        assertThat(json.at("/totals").has("errors")).describedAs("totals.errors field missing").isTrue();
        
        // Metrics fields - check if SET exists and has at least one recorded operation
        assertThat(json.has("metrics")).describedAs("metrics field missing").isTrue();
        
        long totalRequests = json.at("/totals/requests").asLong();
        assertThat(totalRequests).isEqualTo(20);
        JsonNode setMetrics = json.at("/metrics/SET");
        assertThat(setMetrics.has("requests")).describedAs("metrics.SET.requests field missing").isTrue();
        assertThat(setMetrics.has("errors")).describedAs("metrics.SET.errors field missing").isTrue();
        assertThat(setMetrics.has("latency")).describedAs("metrics.SET.latency field missing").isTrue();
            
        JsonNode latency = setMetrics.at("/latency");
        long latencyCount = latency.at("/count").asLong();
        assertThat(latencyCount).isEqualTo(20);
        assertThat(latency.has("unit")).describedAs("latency.unit field missing").isTrue();
        assertThat(latency.has("count")).describedAs("latency.count field missing").isTrue();
        assertThat(latency.has("summary")).describedAs("latency.summary field missing").isTrue();
        assertThat(latency.has("hdr")).describedAs("latency.hdr field missing").isTrue();
                
                // Summary fields
        JsonNode summary = latency.at("/summary");
        assertThat(summary.has("min")).describedAs("summary.min field missing").isTrue();
        assertThat(summary.has("p50")).describedAs("summary.p50 field missing").isTrue();
        assertThat(summary.has("p95")).describedAs("summary.p95 field missing").isTrue();
        assertThat(summary.has("p99")).describedAs("summary.p99 field missing").isTrue();
        assertThat(summary.has("p999")).describedAs("summary.p999 field missing").isTrue();
        assertThat(summary.has("max")).describedAs("summary.max field missing").isTrue();
        
        // HDR fields
        JsonNode hdr = latency.at("/hdr");
        assertThat(hdr.has("format")).describedAs("hdr.format field missing").isTrue();
        assertThat(hdr.has("sigfig")).describedAs("hdr.sigfig field missing").isTrue();
        assertThat(hdr.has("payload_b64")).describedAs("hdr.payload_b64 field missing").isTrue();
    }

    // === Latency Measurement Validation Tests ===

    /**
     * Validates that latency measurements are accurate for all drivers.
     * 
     * <p>This test ensures that the TimedResult latency values returned by all drivers
     * represent actual command execution time (not queue wait time), and p50 should be
     * under 1ms (1000 microseconds) for localhost connections.</p>
     * 
     * <p>This test was introduced to catch and prevent latency measurement bugs where
     * latency was incorrectly measured from command submission to completion (including
     * thread pool queue wait time) instead of actual Redis command execution time.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allDrivers")
    void latencyP50ShouldBeLessThan1ms(String driverName, String driverJson) throws Exception {
        // Run enough operations to get meaningful latency statistics
        int targetRequests = 10000;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "LatencyValidationTest"},
                "phases": [{
                    "id": "LATENCY_VALIDATION",
                    "description": "Validate latency measurements are accurate",
                    "connections": 1,
                    "commands": [
                        {"command": "set", "weight": 0.5, "data_size_bytes": 32},
                        {"command": "get", "weight": 0.5}
                    ],
                    "keyspace": {
                        "key_prefix": "latval:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, targetRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("latency-validation-" + driverName + ".ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // 1ms = 1000 microseconds - this is a generous limit for localhost operations
        // Typical localhost p50 should be 100-500µs
        final long MAX_P50_LATENCY_MICROS = 1000;
        
        // Verify SET latency p50 < 1ms
        long setP50 = json.at("/metrics/SET/latency/summary/p50").asLong();
        assertThat(setP50)
            .describedAs("SET p50 latency for %s should be < %d µs (1ms), but was %d µs (%.2f ms). " +
                        "This may indicate latency is being measured incorrectly (e.g., including queue wait time).",
                        driverName, MAX_P50_LATENCY_MICROS, setP50, setP50 / 1000.0)
            .isLessThan(MAX_P50_LATENCY_MICROS);
        
        // Verify GET latency p50 < 1ms
        long getP50 = json.at("/metrics/GET/latency/summary/p50").asLong();
        assertThat(getP50)
            .describedAs("GET p50 latency for %s should be < %d µs (1ms), but was %d µs (%.2f ms). " +
                        "This may indicate latency is being measured incorrectly (e.g., including queue wait time).",
                        driverName, MAX_P50_LATENCY_MICROS, getP50, getP50 / 1000.0)
            .isLessThan(MAX_P50_LATENCY_MICROS);
        
        // Also verify the values are not in nanoseconds (would be 1000x higher)
        // If values were in nanoseconds, p50 would likely be > 100,000
        assertThat(setP50)
            .describedAs("SET latency for %s appears to be in nanoseconds instead of microseconds", driverName)
            .isLessThan(100_000);
        assertThat(getP50)
            .describedAs("GET latency for %s appears to be in nanoseconds instead of microseconds", driverName)
            .isLessThan(100_000);
    }

    // === Parallel Command Issuer Tests (Recording Client Only) ===

    /**
     * Validates that multiple parallel command issuer threads produce the exact
     * target request count with no over-submission.
     * 
     * <p>Uses the recording client with 64 connections and forces 4 issuer threads.
     * The atomic claim mechanism in submitRequestWithBackpressure must ensure
     * exactly targetRequests are submitted despite concurrent threads.</p>
     */
    @Test
    void parallelIssuersProduceExactRequestCount() throws Exception {
        final int totalRequests = 10_000;
        
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "latency_distribution": "log_normal",
                    "latency_min_ms": 10,
                    "latency_median_ms": 20,
                    "latency_p9999_target_ms": 100
                }
            }
            """;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "ParallelIssuerExactCountTest"},
                "phases": [{
                    "id": "PARALLEL_EXACT_COUNT",
                    "description": "Test exact request count with parallel issuers",
                    "connections": 64,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "pexact:",
                        "keys_count": 1000,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, totalRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("parallel-exact-count.ndjson");
        // Force 4 issuer threads
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString(), null, 4);
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Strict exact count assertion — validates atomic claim prevents over-submission
        assertThat(json.at("/totals/requests").asLong())
            .describedAs("Parallel issuers must produce exact request count (no over-submission)")
            .isEqualTo(totalRequests);
        
        assertThat(json.at("/metrics/SET/requests").asLong()).isEqualTo(totalRequests);
        assertThat(json.at("/metrics/SET/latency/count").asLong()).isEqualTo(totalRequests);
        assertThat(json.at("/phase/status").asText()).isEqualTo("COMPLETED");
    }

    /**
     * Validates that parallel command issuers preserve histogram accuracy.
     * 
     * <p>Uses the recording client with a known log-normal distribution,
     * 100 connections and 4 issuer threads. Verifies that concurrent
     * histogram recording doesn't corrupt the data.</p>
     */
    @Test
    void parallelIssuersPreserveLatencyAccuracy() throws Exception {
        final int totalRequests = 50_000;
        final long medianMs = 100;
        final long p9999TargetMs = 500;
        
        String driverJson = String.format("""
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "latency_distribution": "log_normal",
                    "latency_min_ms": 30,
                    "latency_median_ms": %d,
                    "latency_p9999_target_ms": %d
                }
            }
            """, medianMs, p9999TargetMs);
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "ParallelIssuerLatencyTest"},
                "phases": [{
                    "id": "PARALLEL_LATENCY",
                    "description": "Test latency accuracy with parallel issuers",
                    "connections": 100,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "plat:",
                        "keys_count": 1000,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, totalRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("parallel-latency.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString(), null, 4);
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Verify exact count
        assertThat(json.at("/totals/requests").asLong()).isEqualTo(totalRequests);
        
        // Decode histogram and verify percentiles
        String base64Payload = json.at("/metrics/SET/latency/hdr/payload_b64").asText();
        byte[] decoded = Base64.getDecoder().decode(base64Payload);
        ByteBuffer buffer = ByteBuffer.wrap(decoded);
        Histogram decodedHistogram = Histogram.decodeFromCompressedByteBuffer(buffer, 0);
        
        assertThat(decodedHistogram.getTotalCount()).isEqualTo(totalRequests);
        
        // Calculate expected p50 from log-normal parameters
        double mu = Math.log(medianMs);
        double sigma = (Math.log(p9999TargetMs) - mu) / 3.72;
        long expectedP50Us = (long) (Math.exp(mu) * 1000); // median in microseconds
        
        long actualP50Us = decodedHistogram.getValueAtPercentile(50.0);
        assertThat(actualP50Us)
            .describedAs("p50 should be close to expected median (~%d µs) with parallel issuers", expectedP50Us)
            .isCloseTo(expectedP50Us, withinPercentage(5));
        
        // Verify ordering invariant
        assertThat(decodedHistogram.getValueAtPercentile(50.0))
            .isLessThanOrEqualTo(decodedHistogram.getValueAtPercentile(95.0));
        assertThat(decodedHistogram.getValueAtPercentile(95.0))
            .isLessThanOrEqualTo(decodedHistogram.getValueAtPercentile(99.0));
    }

    /**
     * Validates that parallel issuers work correctly when there are fewer
     * connections than issuer threads (some partitions will be empty).
     */
    @Test
    void parallelIssuersWithFewerConnectionsThanThreads() throws Exception {
        final int totalRequests = 500;
        
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "latency_distribution": "log_normal",
                    "latency_min_ms": 5,
                    "latency_median_ms": 10,
                    "latency_p9999_target_ms": 50
                }
            }
            """;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "ParallelIssuerFewConnsTest"},
                "phases": [{
                    "id": "FEW_CONNS",
                    "description": "Test parallel issuers with fewer connections than threads",
                    "connections": 2,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "fewconn:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, totalRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("parallel-few-conns.ndjson");
        // Force 8 threads with only 2 connections — 6 partitions will be empty
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString(), null, 8);
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        assertThat(json.at("/totals/requests").asLong()).isEqualTo(totalRequests);
        assertThat(json.at("/phase/status").asText()).isEqualTo("COMPLETED");
    }

    /**
     * Validates that forcing 1 issuer thread (single-threaded path)
     * still produces exact request counts — baseline sanity check.
     */
    @Test
    void singleIssuerThreadProducesExactCount() throws Exception {
        final int totalRequests = 5_000;
        
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "latency_distribution": "log_normal",
                    "latency_min_ms": 5,
                    "latency_median_ms": 15,
                    "latency_p9999_target_ms": 80
                }
            }
            """;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "SingleIssuerExactCountTest"},
                "phases": [{
                    "id": "SINGLE_ISSUER",
                    "description": "Baseline: single issuer thread exact count",
                    "connections": 10,
                    "commands": [
                        {"command": "set", "weight": 0.5, "data_size_bytes": 32},
                        {"command": "get", "weight": 0.5}
                    ],
                    "keyspace": {
                        "key_prefix": "single:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, totalRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("single-issuer-exact.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString(), null, 1);
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        assertThat(json.at("/totals/requests").asLong()).isEqualTo(totalRequests);
        
        long setRequests = json.at("/metrics/SET/requests").asLong();
        long getRequests = json.at("/metrics/GET/requests").asLong();
        assertThat(setRequests + getRequests).isEqualTo(totalRequests);
        assertThat(json.at("/phase/status").asText()).isEqualTo("COMPLETED");
    }

    /**
     * Validates that parallel issuers work with duration-based completion.
     * All threads should contribute requests and complete cleanly.
     */
    @Test
    void parallelIssuersWithDurationBasedCompletion() throws Exception {
        String driverJson = """
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "latency_distribution": "log_normal",
                    "latency_min_ms": 5,
                    "latency_median_ms": 10,
                    "latency_p9999_target_ms": 50
                }
            }
            """;
        
        String workloadJson = """
            {
                "benchmark_profile": {"name": "ParallelDurationTest"},
                "phases": [{
                    "id": "PARALLEL_DURATION",
                    "description": "Test parallel issuers with duration-based completion",
                    "connections": 32,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "pdur:",
                        "keys_count": 500,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "duration", "seconds": 2}
                }]
            }
            """;
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("parallel-duration.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString(), null, 2);
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        // Duration-based: just verify some requests were made and it completed
        long totalRequests = json.at("/totals/requests").asLong();
        assertThat(totalRequests).isGreaterThan(0);
        assertThat(json.at("/phase/status").asText()).isEqualTo("COMPLETED");
        assertThat(json.at("/phase/duration_ms").asLong()).isGreaterThanOrEqualTo(1900); // ~2 seconds
    }

    // === Long-Tail Latency Distribution Test (Recording Client Only) ===

    @Test
    void latencyHistogramWithLongTailDistribution() throws Exception {
        // Use recording client with log-normal distribution
        // 1000 connections × 100 requests = 100,000 measurements
        // (Using 100K samples provides better statistical stability for extreme percentiles like p99.9)
        // 
        // Log-normal distribution parameters:
        //   latency_min_ms = 50 (floor for OS sleep safety, affects ~1.1% of samples)
        //   latency_median_ms = 150 (exp(mu) = 150, so mu = ln(150))
        //   latency_p9999_target_ms = 900 (used to calculate sigma)
        //
        // sigma = (ln(900) - ln(150)) / 3.72 ≈ 0.4817
        
        final long latencyMinMs = 50;
        final long medianMs = 150;
        final long p9999TargetMs = 900;
        final int totalRequests = 10_000_0;
        
        String driverJson = String.format("""
            {
                "driver_id": "recording",
                "mode": "standalone",
                "specific_driver_config": {
                    "latency_distribution": "log_normal",
                    "latency_min_ms": %d,
                    "latency_median_ms": %d,
                    "latency_p9999_target_ms": %d
                }
            }
            """, latencyMinMs, medianMs, p9999TargetMs);
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "LongTailLatencyTest"},
                "phases": [{
                    "id": "LONG_TAIL_HISTOGRAM",
                    "description": "Test long-tail latency distribution",
                    "connections": 1000,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "longtail:",
                        "keys_count": 10000,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, totalRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(driverJson);
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("longtail-histogram.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(host, port, driver, workload, metricsFile.toString());
        engine.run();
        
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        long totalRequestsActual = json.at("/totals/requests").asLong();
        assertThat(totalRequestsActual).isEqualTo(totalRequests);
        
        // Verify we can decode the histogram
        String base64Payload = json.at("/metrics/SET/latency/hdr/payload_b64").asText();
        byte[] decoded = Base64.getDecoder().decode(base64Payload);
        ByteBuffer buffer = ByteBuffer.wrap(decoded);
        Histogram decodedHistogram = Histogram.decodeFromCompressedByteBuffer(buffer, 0);
        
        // Verify total count is exact (thread-safety fix validation)
        assertThat(decodedHistogram.getTotalCount()).isEqualTo(totalRequests);
        
        // === Calculate expected percentiles using log-normal distribution math ===
        // Same formula as RecordingBenchmarkClient:
        //   mu = ln(median_ms)
        //   sigma = (ln(p9999_target_ms) - mu) / 3.72
        //   percentile_value = exp(mu + z_score * sigma)
        
        double mu = Math.log(medianMs);
        double sigma = (Math.log(p9999TargetMs) - mu) / 3.72;
        
        // Z-scores for standard normal distribution
        final double Z_P50 = 0.0;
        final double Z_P90 = 1.2816;
        final double Z_P95 = 1.6449;
        final double Z_P99 = 2.3263;
        final double Z_P999 = 3.0902;
        
        // Expected percentile values in microseconds (ms * 1000)
        long expectedP50Us = (long) (Math.exp(mu + Z_P50 * sigma) * 1000);
        long expectedP90Us = (long) (Math.exp(mu + Z_P90 * sigma) * 1000);
        long expectedP95Us = (long) (Math.exp(mu + Z_P95 * sigma) * 1000);
        long expectedP99Us = (long) (Math.exp(mu + Z_P99 * sigma) * 1000);
        long expectedP999Us = (long) (Math.exp(mu + Z_P999 * sigma) * 1000);
        
        // Get actual percentile values from decoded histogram
        long actualP50Us = decodedHistogram.getValueAtPercentile(50.0);
        long actualP90Us = decodedHistogram.getValueAtPercentile(90.0);
        long actualP95Us = decodedHistogram.getValueAtPercentile(95.0);
        long actualP99Us = decodedHistogram.getValueAtPercentile(99.0);
        long actualP999Us = decodedHistogram.getValueAtPercentile(99.9);
        
        // Assert each percentile correlates within 3% of expected value
        // Using Percentage.withPercentage() from AssertJ
        assertThat(actualP50Us)
            .describedAs("p50: expected ~%d µs (%.1f ms)", expectedP50Us, expectedP50Us / 1000.0)
            .isCloseTo(expectedP50Us, withinPercentage(3));
        
        assertThat(actualP90Us)
            .describedAs("p90: expected ~%d µs (%.1f ms)", expectedP90Us, expectedP90Us / 1000.0)
            .isCloseTo(expectedP90Us, withinPercentage(3));
        
        assertThat(actualP95Us)
            .describedAs("p95: expected ~%d µs (%.1f ms)", expectedP95Us, expectedP95Us / 1000.0)
            .isCloseTo(expectedP95Us, withinPercentage(3));
        
        assertThat(actualP99Us)
            .describedAs("p99: expected ~%d µs (%.1f ms)", expectedP99Us, expectedP99Us / 1000.0)
            .isCloseTo(expectedP99Us, withinPercentage(3));
        
        assertThat(actualP999Us)
            .describedAs("p99.9: expected ~%d µs (%.1f ms)", expectedP999Us, expectedP999Us / 1000.0)
            .isCloseTo(expectedP999Us, withinPercentage(3));
        
        // Verify min is >= floor (50ms = 50,000µs, allow 1ms tolerance)
        assertThat(decodedHistogram.getMinValue())
            .describedAs("min should be >= floor (%d ms)", latencyMinMs)
            .isGreaterThanOrEqualTo((latencyMinMs - 1) * 1000);
        
        // Verify ordering invariant: min <= p50 <= p90 <= p95 <= p99 <= p999 <= max
        assertThat(decodedHistogram.getMinValue()).isLessThanOrEqualTo(actualP50Us);
        assertThat(actualP50Us).isLessThanOrEqualTo(actualP90Us);
        assertThat(actualP90Us).isLessThanOrEqualTo(actualP95Us);
        assertThat(actualP95Us).isLessThanOrEqualTo(actualP99Us);
        assertThat(actualP99Us).isLessThanOrEqualTo(actualP999Us);
        assertThat(actualP999Us).isLessThanOrEqualTo(decodedHistogram.getMaxValue());
    }
}