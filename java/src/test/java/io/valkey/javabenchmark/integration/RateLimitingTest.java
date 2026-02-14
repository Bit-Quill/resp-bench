/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkey.javabenchmark.client.impl.RecordingBenchmarkClient;
import io.valkey.javabenchmark.config.*;
import io.valkey.javabenchmark.engine.BenchmarkEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Black-box integration tests that validate rate limiting functionality (cps_limit and rps_limit).
 * 
 * <p>These tests follow the black-box testing approach:</p>
 * <ol>
 *   <li>Define driver and workflow JSON strings with required configuration</li>
 *   <li>Parse configs using ConfigLoader (same as Main.java does)</li>
 *   <li>Run the BenchmarkEngine directly with recording client</li>
 *   <li>Validate using NDJSON metrics output (duration, request counts)</li>
 * </ol>
 * 
 * <p>All tests use the recording client which has no network latency, making rate limiting
 * the dominant factor in execution time.</p>
 * 
 * <p>Rate limit tolerance is set to 5% to ensure reliable rate control. The minimum reliable
 * sleep period is 50ms, so rate limits of 20/sec (50ms between ops) are used for precise testing.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RateLimitingTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6379;
    private static final double RATE_TOLERANCE_PERCENT = 5.0;
    
    private ObjectMapper objectMapper;
    
    @TempDir
    Path tempDir;

    @BeforeAll
    void setup() {
        objectMapper = new ObjectMapper();
    }

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

    // === RPS Limit Tests (Requests Per Second) ===

    @Test
    void rpsLimitControlsRequestRate() throws Exception {
        // 20 rps = 50ms between operations (minimum reliable sleep period)
        // 3 second duration = ~60 expected requests
        int targetRps = 20;
        int durationSeconds = 3;
        int expectedRequests = targetRps * durationSeconds;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "RpsLimitTest"},
                "phases": [{
                    "id": "RPS_LIMITED",
                    "description": "Test RPS rate limiting",
                    "connections": 1,
                    "rps_limit": %d,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "rps:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "duration", "seconds": %d}
                }]
            }
            """, targetRps, durationSeconds);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("rps-limit.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via NDJSON output
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        long totalRequests = json.at("/totals/requests").asLong();
        long durationMs = json.at("/phase/duration_ms").asLong();
        
        // Calculate actual rate
        double actualRate = totalRequests / (durationMs / 1000.0);

        // Assert rate is within 5% of target
        assertThat(actualRate)
            .describedAs("Actual rate %.2f should be within 5%% of target %d", actualRate, targetRps)
            .isCloseTo(targetRps, withinPercentage(RATE_TOLERANCE_PERCENT));
        
        // Assert request count is approximately expected
        assertThat(totalRequests)
            .describedAs("Total requests should be approximately %d", expectedRequests)
            .isCloseTo(expectedRequests, withinPercentage(RATE_TOLERANCE_PERCENT));
    }

    @Test
    void rpsLimitWithRequestBasedCompletion() throws Exception {
        // 20 rps with 60 requests = expected ~3 seconds duration
        int targetRps = 20;
        int targetRequests = 60;
        long expectedDurationMs = (targetRequests * 1000L) / targetRps;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "RpsRequestBasedTest"},
                "phases": [{
                    "id": "RPS_REQUEST_BASED",
                    "description": "Test RPS limit with request-based completion",
                    "connections": 1,
                    "rps_limit": %d,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "rpsreq:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, targetRps, targetRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("rps-request-based.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via NDJSON output
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        long totalRequests = json.at("/totals/requests").asLong();
        long durationMs = json.at("/phase/duration_ms").asLong();
        
        // Assert exact request count
        assertThat(totalRequests).isEqualTo(targetRequests);
        
        // Assert duration is within 5% of expected
        assertThat(durationMs)
            .describedAs("Duration %dms should be within 5%% of expected %dms", durationMs, expectedDurationMs)
            .isCloseTo(expectedDurationMs, withinPercentage(RATE_TOLERANCE_PERCENT));
        
        // Verify rate
        double actualRate = totalRequests / (durationMs / 1000.0);
        assertThat(actualRate)
            .describedAs("Actual rate %.2f should be within 5%% of target %d", actualRate, targetRps)
            .isCloseTo(targetRps, withinPercentage(RATE_TOLERANCE_PERCENT));
    }

    @Test
    void multipleConnectionsWithSharedRpsLimit() throws Exception {
        // 20 rps shared across 4 connections
        // Total throughput should still be ~20 rps (not 20 per connection)
        int targetRps = 20;
        int connections = 4;
        int durationSeconds = 3;
        int expectedRequests = targetRps * durationSeconds;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "MultiConnRpsTest"},
                "phases": [{
                    "id": "MULTI_CONN_RPS",
                    "description": "Test RPS limit shared across multiple connections",
                    "connections": %d,
                    "rps_limit": %d,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "multiconn:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "duration", "seconds": %d}
                }]
            }
            """, connections, targetRps, durationSeconds);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("multi-conn-rps.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via NDJSON output
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        long totalRequests = json.at("/totals/requests").asLong();
        long durationMs = json.at("/phase/duration_ms").asLong();
        int actualConnections = json.at("/phase/connections").asInt();
        
        // Verify connections were created
        assertThat(actualConnections).isEqualTo(connections);
        
        // Calculate actual rate (should be ~20 rps total, not 80)
        double actualRate = totalRequests / (durationMs / 1000.0);
        
        // Assert rate is within 5% of target (shared limit)
        assertThat(actualRate)
            .describedAs("Actual rate %.2f should be within 5%% of shared target %d", actualRate, targetRps)
            .isCloseTo(targetRps, withinPercentage(RATE_TOLERANCE_PERCENT));
    }

    // === CPS Limit Tests (Connections Per Second) ===

    @Test
    void cpsLimitControlsConnectionRate() throws Exception {
        // 10 cps = 100ms between connections
        // 20 connections = expected ~2 seconds for connection establishment
        // With 10 requests (fast with no delay), total time should be dominated by connection time
        int targetCps = 10;
        int connections = 20;
        int requests = 10; // Small number, fast completion
        long expectedConnectionTimeMs = (connections * 1000L) / targetCps;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "CpsLimitTest"},
                "phases": [{
                    "id": "CPS_LIMITED",
                    "description": "Test CPS rate limiting",
                    "connections": %d,
                    "cps_limit": %d,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "cps:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, connections, targetCps, requests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("cps-limit.ndjson");
        
        // Measure wall clock time (includes connection establishment + request execution)
        long startTime = System.currentTimeMillis();
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        long totalWallClockMs = System.currentTimeMillis() - startTime;
        
        // Validate via NDJSON output
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        long totalRequests = json.at("/totals/requests").asLong();
        long requestDurationMs = json.at("/phase/duration_ms").asLong();
        int actualConnections = json.at("/phase/connections").asInt();
        
        // Verify connections count in output
        assertThat(actualConnections).isEqualTo(connections);
        assertThat(totalRequests).isEqualTo(requests);
        
        // Wall clock time = connection time + request time
        // Request time should be very small (no rate limit, recording client)
        // So total time should be dominated by connection establishment
        // Expected: ~2 sec connection + ~0 sec requests = ~2 sec total
        assertThat(totalWallClockMs)
            .describedAs("Total time %dms should be >= expected connection time %dms", 
                        totalWallClockMs, expectedConnectionTimeMs)
            .isGreaterThanOrEqualTo((long)(expectedConnectionTimeMs * 0.95));
        
        // Request phase should be fast (no rps limit)
        assertThat(requestDurationMs)
            .describedAs("Request phase should be fast (< 500ms) without RPS limit")
            .isLessThan(500L);
    }

    // === No Rate Limit Tests ===

    @Test
    void noRateLimitAllowsMaximumThroughput() throws Exception {
        // No rps_limit (or -1) = unlimited throughput
        // Recording client has no network latency, so should complete very fast
        int targetRequests = 1000;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "NoRateLimitTest"},
                "phases": [{
                    "id": "NO_LIMIT",
                    "description": "Test unlimited throughput without rate limit",
                    "connections": 1,
                    "rps_limit": -1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "nolimit:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, targetRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("no-limit.ndjson");
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        
        // Validate via NDJSON output
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        long totalRequests = json.at("/totals/requests").asLong();
        long durationMs = json.at("/phase/duration_ms").asLong();
        
        // Assert all requests completed
        assertThat(totalRequests).isEqualTo(targetRequests);
        
        // Assert very fast completion (should be < 1 second for 1000 ops with no delay)
        assertThat(durationMs)
            .describedAs("Duration %dms should be fast (< 1s) without rate limiting", durationMs)
            .isLessThan(1000L);
        
        // Calculate actual rate - should be very high
        double actualRate = totalRequests / (durationMs / 1000.0);
        assertThat(actualRate)
            .describedAs("Actual rate %.2f should be high (> 1000 rps) without rate limiting", actualRate)
            .isGreaterThan(1000.0);
    }

    @Test
    void noRateLimitMuchFasterThanRateLimited() throws Exception {
        // Compare execution time: rate-limited vs unlimited
        // This demonstrates that rate limiting is actually working
        int targetRequests = 100;
        int rpsLimit = 20;
        
        // First run: with rate limit
        String rateLimitedWorkloadJson = String.format("""
            {
                "benchmark_profile": {"name": "RateLimitedComparison"},
                "phases": [{
                    "id": "RATE_LIMITED_COMPARE",
                    "description": "Rate limited comparison",
                    "connections": 1,
                    "rps_limit": %d,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "compare:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, rpsLimit, targetRequests);
        
        DriverConfig driver1 = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload1 = ConfigLoader.parseWorkloadConfig(rateLimitedWorkloadJson);
        
        Path metricsFile1 = tempDir.resolve("compare-limited.ndjson");
        BenchmarkEngine engine1 = new BenchmarkEngine(HOST, PORT, driver1, workload1, metricsFile1.toString());
        engine1.run();
        
        String line1 = Files.readString(metricsFile1).trim();
        JsonNode json1 = objectMapper.readTree(line1);
        long rateLimitedDurationMs = json1.at("/phase/duration_ms").asLong();
        
        // Clear instances for second run
        RecordingBenchmarkClient.clearInstances();
        
        // Second run: without rate limit
        String unlimitedWorkloadJson = String.format("""
            {
                "benchmark_profile": {"name": "UnlimitedComparison"},
                "phases": [{
                    "id": "UNLIMITED_COMPARE",
                    "description": "Unlimited comparison",
                    "connections": 1,
                    "rps_limit": -1,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "compare:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, targetRequests);
        
        DriverConfig driver2 = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload2 = ConfigLoader.parseWorkloadConfig(unlimitedWorkloadJson);
        
        Path metricsFile2 = tempDir.resolve("compare-unlimited.ndjson");
        BenchmarkEngine engine2 = new BenchmarkEngine(HOST, PORT, driver2, workload2, metricsFile2.toString());
        engine2.run();
        
        String line2 = Files.readString(metricsFile2).trim();
        JsonNode json2 = objectMapper.readTree(line2);
        long unlimitedDurationMs = json2.at("/phase/duration_ms").asLong();
        
        // Rate limited should take ~5 seconds (100 reqs at 20 rps)
        long expectedRateLimitedMs = (targetRequests * 1000L) / rpsLimit;
        assertThat(rateLimitedDurationMs)
            .describedAs("Rate-limited duration %dms should be ~%dms", rateLimitedDurationMs, expectedRateLimitedMs)
            .isCloseTo(expectedRateLimitedMs, withinPercentage(RATE_TOLERANCE_PERCENT));
        
        // Unlimited should be MUCH faster (at least 10x faster)
        assertThat(unlimitedDurationMs)
            .describedAs("Unlimited duration %dms should be much faster than rate-limited %dms", 
                        unlimitedDurationMs, rateLimitedDurationMs)
            .isLessThan(rateLimitedDurationMs / 10);
    }

    // === Combined CPS + RPS Limit Tests ===

    @Test
    void combinedCpsAndRpsLimitsWorkTogether() throws Exception {
        // Both CPS and RPS limits active
        // CPS limit controls connection creation rate
        // RPS limit controls request rate after connections are established
        int targetCps = 10;
        int targetRps = 20;
        int connections = 10;
        int targetRequests = 40;
        
        // Expected: ~1 sec for connections (10 at 10/sec), ~2 sec for requests (40 at 20/sec)
        long expectedConnectionTimeMs = (connections * 1000L) / targetCps;
        long expectedRequestTimeMs = (targetRequests * 1000L) / targetRps;
        long expectedTotalTimeMs = expectedConnectionTimeMs + expectedRequestTimeMs;
        
        String workloadJson = String.format("""
            {
                "benchmark_profile": {"name": "CombinedLimitsTest"},
                "phases": [{
                    "id": "COMBINED_LIMITS",
                    "description": "Test combined CPS and RPS limits",
                    "connections": %d,
                    "cps_limit": %d,
                    "rps_limit": %d,
                    "commands": [{"command": "set", "weight": 1.0, "data_size_bytes": 32}],
                    "keyspace": {
                        "key_prefix": "combined:",
                        "keys_count": 100,
                        "key_size_bytes": 16,
                        "generation_alg": "sequential_int"
                    },
                    "completion": {"type": "requests", "requests": %d}
                }]
            }
            """, connections, targetCps, targetRps, targetRequests);
        
        DriverConfig driver = ConfigLoader.parseDriverConfig(recordingDriverJson());
        WorkloadConfig workload = ConfigLoader.parseWorkloadConfig(workloadJson);
        
        Path metricsFile = tempDir.resolve("combined-limits.ndjson");
        
        // Measure wall clock time (includes connection establishment + request execution)
        long startTime = System.currentTimeMillis();
        BenchmarkEngine engine = new BenchmarkEngine(HOST, PORT, driver, workload, metricsFile.toString());
        engine.run();
        long totalWallClockMs = System.currentTimeMillis() - startTime;
        
        // Validate via NDJSON output
        String line = Files.readString(metricsFile).trim();
        JsonNode json = objectMapper.readTree(line);
        
        long totalRequests = json.at("/totals/requests").asLong();
        long requestDurationMs = json.at("/phase/duration_ms").asLong();
        int actualConnections = json.at("/phase/connections").asInt();
        
        // Assert all requests completed
        assertThat(totalRequests).isEqualTo(targetRequests);
        assertThat(actualConnections).isEqualTo(connections);
        
        // Verify RPS was respected during request phase
        // duration_ms is for request phase only
        assertThat(requestDurationMs)
            .describedAs("Request phase duration %dms should be within 5%% of expected %dms", 
                        requestDurationMs, expectedRequestTimeMs)
            .isCloseTo(expectedRequestTimeMs, withinPercentage(RATE_TOLERANCE_PERCENT));
        
        // Verify total time includes both CPS and RPS limited phases
        // Total = connection time (~1s) + request time (~2s) = ~3s
        assertThat(totalWallClockMs)
            .describedAs("Total time %dms should be within 5%% of expected %dms (connection + request)", 
                        totalWallClockMs, expectedTotalTimeMs)
            .isCloseTo(expectedTotalTimeMs, withinPercentage(RATE_TOLERANCE_PERCENT));
    }
}
