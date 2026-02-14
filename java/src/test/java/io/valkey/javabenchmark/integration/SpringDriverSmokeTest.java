/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.integration;

import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.BenchmarkClientFactory;
import io.valkey.javabenchmark.client.TimedResult;
import io.valkey.javabenchmark.config.ConfigLoader;
import io.valkey.javabenchmark.config.DriverConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Smoke tests for Spring Data drivers to validate basic connectivity and operations.
 * These tests are designed to quickly identify issues with Spring driver configurations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SpringDriverSmokeTest {

    private static String host;
    private static int port;

    @BeforeAll
    void setup() {
        host = System.getProperty("valkey.host", "localhost");
        if (host.isEmpty()) {
            host = "localhost";
        }
        String portStr = System.getProperty("valkey.port", "6379");
        port = Integer.parseInt(portStr.isEmpty() ? "6379" : portStr);
    }

    /**
     * Provides all Spring driver configurations for testing.
     */
    static Stream<Arguments> springDrivers() {
        return Stream.of(
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("springDrivers")
    void canConnectAndPing(String driverName, String driverJson) throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig(driverJson);
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            
            CompletableFuture<TimedResult<String>> pingFuture = client.ping();
            String response = pingFuture.get(5, TimeUnit.SECONDS).getValue();
            assertThat(response).isEqualTo("PONG");
        } finally {
            client.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("springDrivers")
    void canSetAndGet(String driverName, String driverJson) throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig(driverJson);
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            byte[] key = ("smoke-test-" + driverName).getBytes();
            byte[] value = "test-value".getBytes();
            
            // Set
            CompletableFuture<TimedResult<Void>> setFuture = client.set(key, value);
            setFuture.get(5, TimeUnit.SECONDS);
            
            // Get
            CompletableFuture<TimedResult<byte[]>> getFuture = client.get(key);
            byte[] result = getFuture.get(5, TimeUnit.SECONDS).getValue();
            
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get(5, TimeUnit.SECONDS);
        } finally {
            client.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("springDrivers")
    void canHandleMultipleConcurrentOperations(String driverName, String driverJson) throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig(driverJson);
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            int numOperations = 100;
            CompletableFuture<?>[] futures = new CompletableFuture[numOperations];
            
            for (int i = 0; i < numOperations; i++) {
                byte[] key = ("concurrent-test-" + driverName + "-" + i).getBytes();
                byte[] value = ("value-" + i).getBytes();
                futures[i] = client.set(key, value);
            }
            
            // Wait for all operations to complete
            CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
            
            // Verify all succeeded
            for (int i = 0; i < numOperations; i++) {
                assertThat(futures[i].isDone()).isTrue();
                assertThat(futures[i].isCompletedExceptionally()).isFalse();
            }
            
            // Cleanup
            for (int i = 0; i < numOperations; i++) {
                byte[] key = ("concurrent-test-" + driverName + "-" + i).getBytes();
                client.del(key).get(5, TimeUnit.SECONDS);
            }
        } finally {
            client.close();
        }
    }
}