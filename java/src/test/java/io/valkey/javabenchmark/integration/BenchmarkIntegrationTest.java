/*
 * Copyright 2025 the original author or authors.
 */
package io.valkey.javabenchmark.integration;

import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.BenchmarkClientFactory;
import io.valkey.javabenchmark.config.ConfigLoader;
import io.valkey.javabenchmark.config.DriverConfig;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import io.valkey.springframework.data.valkey.connection.valkeyglide.ValkeyGlideConnectionFactory;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests that require a running Valkey/Redis server.
 * Server endpoint can be configured via system properties:
 * 
 * <h3>Standalone Mode</h3>
 * <ul>
 *   <li>valkey.host (default: localhost)</li>
 *   <li>valkey.port (default: 6379)</li>
 * </ul>
 * 
 * <h3>Cluster Mode</h3>
 * <ul>
 *   <li>valkey.cluster.host (default: localhost)</li>
 *   <li>valkey.cluster.port (default: 7379)</li>
 * </ul>
 * 
 * <h3>Running Tests</h3>
 * <pre>
 * # Run all tests (standalone and cluster)
 * mvn test -Dvalkey.host=localhost -Dvalkey.port=6379 -Dvalkey.cluster.host=localhost -Dvalkey.cluster.port=7379
 * 
 * # Run only standalone tests
 * mvn test -DexcludedGroups=cluster
 * 
 * # Run only cluster tests
 * mvn test -Dgroups=cluster
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BenchmarkIntegrationTest {

    private static String host;
    private static int port;
    private static String clusterHost;
    private static int clusterPort;

    @BeforeAll
    static void setup() {
        // Standalone configuration
        host = System.getProperty("valkey.host", "localhost");
        if (host.isEmpty()) {
            host = "localhost";
        }
        String portStr = System.getProperty("valkey.port", "6379");
        port = Integer.parseInt(portStr.isEmpty() ? "6379" : portStr);
        
        // Cluster configuration
        clusterHost = System.getProperty("valkey.cluster.host", "localhost");
        if (clusterHost.isEmpty()) {
            clusterHost = "localhost";
        }
        String clusterPortStr = System.getProperty("valkey.cluster.port", "7379");
        clusterPort = Integer.parseInt(clusterPortStr.isEmpty() ? "7379" : clusterPortStr);
    }

    /**
     * Use reflection to verify that the BenchmarkClient is using the expected underlying connection factory.
     * This ensures that secondary_driver_id configuration is being respected.
     * 
     * @param client the BenchmarkClient to inspect
     * @param expectedFactoryType the expected type of the internal connectionFactory field
     */
    private void assertUnderlyingConnectionFactory(BenchmarkClient client, Class<?> expectedFactoryType) throws Exception {
        // Spring Data clients store connectionFactory inside static SharedState,
        // accessed via the static sharedStateRef (AtomicReference<SharedState>)
        Field sharedStateRefField = client.getClass().getDeclaredField("sharedStateRef");
        sharedStateRefField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<?> sharedStateRef = (AtomicReference<?>) sharedStateRefField.get(null); // static field
        Object sharedState = sharedStateRef.get();
        assertThat(sharedState).describedAs("SharedState should not be null after connect").isNotNull();
        
        Field connectionFactoryField = sharedState.getClass().getDeclaredField("connectionFactory");
        connectionFactoryField.setAccessible(true);
        Object connectionFactory = connectionFactoryField.get(sharedState);
        
        assertThat(connectionFactory)
            .describedAs("Expected connectionFactory to be %s but was %s", 
                expectedFactoryType.getSimpleName(), 
                connectionFactory != null ? connectionFactory.getClass().getSimpleName() : "null")
            .isInstanceOf(expectedFactoryType);
    }

    @Test
    void jedisClientShouldConnect() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "jedis", "mode": "standalone"}
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Test SET/GET
            byte[] key = "integration-test-key".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    void lettuceClientShouldConnect() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "lettuce", "mode": "standalone"}
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
        } finally {
            client.close();
        }
    }

    @Test
    void valkeyGlideClientShouldConnect() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "valkey-glide", "mode": "standalone"}
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
        } finally {
            client.close();
        }
    }

    @Test
    void redissonClientShouldConnect() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "redisson", "mode": "standalone"}
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Test SET/GET
            byte[] key = "redisson-integration-test-key".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    void springDataRedisWithJedisShouldConnect() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {
                "driver_id": "spring-data-redis",
                "mode": "standalone",
                "specific_driver_config": {
                    "secondary_driver_id": "jedis"
                }
            }
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Verify the underlying driver matches configuration
            assertUnderlyingConnectionFactory(client, JedisConnectionFactory.class);
            
            // Test SET/GET
            byte[] key = "spring-data-redis-jedis-test".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    void springDataRedisWithLettuceShouldConnect() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {
                "driver_id": "spring-data-redis",
                "mode": "standalone",
                "specific_driver_config": {
                    "secondary_driver_id": "lettuce"
                }
            }
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Verify the underlying driver matches configuration - SHOULD FAIL until impl is fixed
            assertUnderlyingConnectionFactory(client, LettuceConnectionFactory.class);
            
            // Test SET/GET
            byte[] key = "spring-data-redis-lettuce-test".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    void springDataValkeyWithJedisShouldConnect() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {
                "driver_id": "spring-data-valkey",
                "mode": "standalone",
                "specific_driver_config": {
                    "secondary_driver_id": "jedis"
                }
            }
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Verify the underlying driver matches configuration (spring-data-valkey's JedisConnectionFactory)
            assertUnderlyingConnectionFactory(client, io.valkey.springframework.data.valkey.connection.jedis.JedisConnectionFactory.class);
            
            // Test SET/GET
            byte[] key = "spring-data-valkey-jedis-test".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    void springDataValkeyWithLettuceShouldConnect() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {
                "driver_id": "spring-data-valkey",
                "mode": "standalone",
                "specific_driver_config": {
                    "secondary_driver_id": "lettuce"
                }
            }
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Verify the underlying driver matches configuration - SHOULD FAIL until impl is fixed
            assertUnderlyingConnectionFactory(client, io.valkey.springframework.data.valkey.connection.lettuce.LettuceConnectionFactory.class);
            
            // Test SET/GET
            byte[] key = "spring-data-valkey-lettuce-test".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    void springDataValkeyWithGlideShouldConnect() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {
                "driver_id": "spring-data-valkey",
                "mode": "standalone",
                "specific_driver_config": {
                    "secondary_driver_id": "valkey-glide"
                }
            }
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(host, port, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Verify the underlying driver matches configuration
            assertUnderlyingConnectionFactory(client, ValkeyGlideConnectionFactory.class);
            
            // Test SET/GET
            byte[] key = "spring-data-valkey-glide-test".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    // ==================== CLUSTER MODE TESTS ====================

    @Test
    @Tag("cluster")
    void jedisClientShouldConnectToCluster() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "jedis", "mode": "cluster"}
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(clusterHost, clusterPort, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Test SET/GET
            byte[] key = "integration-test-key-cluster".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    @Tag("cluster")
    void lettuceClientShouldConnectToCluster() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "lettuce", "mode": "cluster"}
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(clusterHost, clusterPort, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
        } finally {
            client.close();
        }
    }

    @Test
    @Tag("cluster")
    void valkeyGlideClientShouldConnectToCluster() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "valkey-glide", "mode": "cluster"}
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(clusterHost, clusterPort, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
        } finally {
            client.close();
        }
    }

    @Test
    @Tag("cluster")
    void redissonClientShouldConnectToCluster() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {"driver_id": "redisson", "mode": "cluster"}
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(clusterHost, clusterPort, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Test SET/GET
            byte[] key = "redisson-cluster-integration-test-key".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    @Tag("cluster")
    void springDataRedisWithJedisShouldConnectToCluster() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {
                "driver_id": "spring-data-redis",
                "mode": "cluster",
                "specific_driver_config": {
                    "secondary_driver_id": "jedis"
                }
            }
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(clusterHost, clusterPort, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Verify the underlying driver matches configuration
            assertUnderlyingConnectionFactory(client, JedisConnectionFactory.class);
            
            // Test SET/GET
            byte[] key = "spring-data-redis-jedis-cluster-test".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    @Tag("cluster")
    void springDataRedisWithLettuceShouldConnectToCluster() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {
                "driver_id": "spring-data-redis",
                "mode": "cluster",
                "specific_driver_config": {
                    "secondary_driver_id": "lettuce"
                }
            }
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(clusterHost, clusterPort, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Verify the underlying driver matches configuration
            assertUnderlyingConnectionFactory(client, LettuceConnectionFactory.class);
            
            // Test SET/GET
            byte[] key = "spring-data-redis-lettuce-cluster-test".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    @Tag("cluster")
    void springDataValkeyWithJedisShouldConnectToCluster() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {
                "driver_id": "spring-data-valkey",
                "mode": "cluster",
                "specific_driver_config": {
                    "secondary_driver_id": "jedis"
                }
            }
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(clusterHost, clusterPort, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Verify the underlying driver matches configuration (spring-data-valkey's JedisConnectionFactory)
            assertUnderlyingConnectionFactory(client, io.valkey.springframework.data.valkey.connection.jedis.JedisConnectionFactory.class);
            
            // Test SET/GET
            byte[] key = "spring-data-valkey-jedis-cluster-test".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    @Tag("cluster")
    void springDataValkeyWithLettuceShouldConnectToCluster() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {
                "driver_id": "spring-data-valkey",
                "mode": "cluster",
                "specific_driver_config": {
                    "secondary_driver_id": "lettuce"
                }
            }
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(clusterHost, clusterPort, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Verify the underlying driver matches configuration
            assertUnderlyingConnectionFactory(client, io.valkey.springframework.data.valkey.connection.lettuce.LettuceConnectionFactory.class);
            
            // Test SET/GET
            byte[] key = "spring-data-valkey-lettuce-cluster-test".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }

    @Test
    @Tag("cluster")
    void springDataValkeyWithGlideShouldConnectToCluster() throws Exception {
        DriverConfig config = ConfigLoader.parseDriverConfig("""
            {
                "driver_id": "spring-data-valkey",
                "mode": "cluster",
                "specific_driver_config": {
                    "secondary_driver_id": "valkey-glide"
                }
            }
            """);
        
        BenchmarkClient client = BenchmarkClientFactory.createAndConnect(clusterHost, clusterPort, config);
        
        try {
            assertThat(client.isConnected()).isTrue();
            assertThat(client.ping().get().getValue()).isEqualTo("PONG");
            
            // Verify the underlying driver matches configuration
            assertUnderlyingConnectionFactory(client, ValkeyGlideConnectionFactory.class);
            
            // Test SET/GET
            byte[] key = "spring-data-valkey-glide-cluster-test".getBytes();
            byte[] value = "test-value".getBytes();
            client.set(key, value).get();
            byte[] result = client.get(key).get().getValue();
            assertThat(result).isEqualTo(value);
            
            // Cleanup
            client.del(key).get();
        } finally {
            client.close();
        }
    }
}
