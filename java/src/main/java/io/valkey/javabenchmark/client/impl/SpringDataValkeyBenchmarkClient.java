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
package io.valkey.javabenchmark.client.impl;

import io.valkey.javabenchmark.client.BenchmarkClient;
import io.valkey.javabenchmark.client.TimedResult;
import io.valkey.javabenchmark.config.DriverConfig;
import io.valkey.springframework.data.valkey.connection.*;
import io.valkey.springframework.data.valkey.connection.jedis.JedisClientConfiguration;
import io.valkey.springframework.data.valkey.connection.jedis.JedisConnectionFactory;
import io.valkey.springframework.data.valkey.connection.lettuce.LettuceClientConfiguration;
import io.valkey.springframework.data.valkey.connection.lettuce.LettuceConnectionFactory;
import io.valkey.springframework.data.valkey.connection.valkeyglide.ValkeyGlideClientConfiguration;
import io.valkey.springframework.data.valkey.connection.valkeyglide.ValkeyGlideConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Spring Data Valkey implementation of BenchmarkClient.
 * Supports Jedis, Lettuce, and ValkeyGlide as underlying drivers via secondary_driver_id configuration.
 * Default: ValkeyGlide if secondary_driver_id is not specified.
 */
public class SpringDataValkeyBenchmarkClient implements BenchmarkClient {

    private static final Logger logger = LoggerFactory.getLogger(SpringDataValkeyBenchmarkClient.class);

    private ValkeyConnectionFactory connectionFactory;
    private ValkeyConnection connection;
    private final ReentrantLock connectionLock = new ReentrantLock();
    private boolean connected;
    private ExecutorService executor;
    private String secondaryDriverId;

    @Override
    public String getDriverId() {
        return "spring-data-valkey";
    }

    @Override
    public String getDescription() {
        return "Spring Data Valkey (requires secondary_driver_id: jedis, lettuce, or valkey-glide)";
    }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) throws ClientException {
        // Get secondary driver id (jedis, lettuce, or valkey-glide)
        this.secondaryDriverId = driverConfig.getSecondaryDriverId();
        if (secondaryDriverId == null || secondaryDriverId.isEmpty()) {
            secondaryDriverId = "valkey-glide"; // Default to valkey-glide
        }
        
        logger.info("Connecting Spring Data Valkey to {}:{} (cluster={}, secondary_driver={})", 
                host, port, driverConfig.isClusterMode(), secondaryDriverId);
        
        try {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            
            switch (secondaryDriverId.toLowerCase()) {
                case "jedis":
                    connectionFactory = createJedisConnectionFactory(host, port, driverConfig);
                    break;
                case "lettuce":
                    connectionFactory = createLettuceConnectionFactory(host, port, driverConfig);
                    break;
                case "valkey-glide":
                    connectionFactory = createValkeyGlideConnectionFactory(host, port, driverConfig);
                    break;
                default:
                    throw new ClientException("Unsupported secondary_driver_id for spring-data-valkey: " + secondaryDriverId + 
                            ". Supported values: jedis, lettuce, valkey-glide");
            }
            
            // Get connection
            connection = connectionFactory.getConnection();
            
            // Test connection
            String pingResponse = connection.ping();
            if (!"PONG".equals(pingResponse)) {
                throw new ClientException("Unexpected ping response: " + pingResponse);
            }
            
            connected = true;
            logger.info("Spring Data Valkey connected successfully using {}", secondaryDriverId);
            
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("Failed to connect Spring Data Valkey to " + host + ":" + port, e);
        }
    }

    private JedisConnectionFactory createJedisConnectionFactory(String host, int port, DriverConfig driverConfig) {
        JedisClientConfiguration.JedisClientConfigurationBuilder configBuilder =
                JedisClientConfiguration.builder();
        
        // Configure TLS if enabled
        if (driverConfig.isTlsEnabled()) {
            configBuilder.useSsl();
        }
        
        JedisClientConfiguration clientConfig = configBuilder.build();
        
        JedisConnectionFactory jedisFactory;
        if (driverConfig.isClusterMode()) {
            ValkeyClusterConfiguration clusterConfig = new ValkeyClusterConfiguration();
            clusterConfig.addClusterNode(new ValkeyNode(host, port));
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    clusterConfig.setUsername(auth.getUsername());
                }
                clusterConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            
            jedisFactory = new JedisConnectionFactory(clusterConfig, clientConfig);
        } else {
            ValkeyStandaloneConfiguration standaloneConfig = new ValkeyStandaloneConfiguration(host, port);
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    standaloneConfig.setUsername(auth.getUsername());
                }
                standaloneConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            
            jedisFactory = new JedisConnectionFactory(standaloneConfig, clientConfig);
        }
        
        // Initialize the factory
        jedisFactory.afterPropertiesSet();
        jedisFactory.start();
        return jedisFactory;
    }

    private LettuceConnectionFactory createLettuceConnectionFactory(String host, int port, DriverConfig driverConfig) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder configBuilder =
                LettuceClientConfiguration.builder();
        
        // Configure TLS if enabled
        if (driverConfig.isTlsEnabled()) {
            configBuilder.useSsl();
        }
        
        LettuceClientConfiguration clientConfig = configBuilder.build();
        
        LettuceConnectionFactory lettuceFactory;
        if (driverConfig.isClusterMode()) {
            ValkeyClusterConfiguration clusterConfig = new ValkeyClusterConfiguration();
            clusterConfig.addClusterNode(new ValkeyNode(host, port));
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    clusterConfig.setUsername(auth.getUsername());
                }
                clusterConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            
            lettuceFactory = new LettuceConnectionFactory(clusterConfig, clientConfig);
        } else {
            ValkeyStandaloneConfiguration standaloneConfig = new ValkeyStandaloneConfiguration(host, port);
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    standaloneConfig.setUsername(auth.getUsername());
                }
                standaloneConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            
            lettuceFactory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        }
        
        // Initialize the factory
        lettuceFactory.afterPropertiesSet();
        lettuceFactory.start();
        return lettuceFactory;
    }

    private ValkeyGlideConnectionFactory createValkeyGlideConnectionFactory(String host, int port, DriverConfig driverConfig) {
        ValkeyGlideClientConfiguration.ValkeyGlideClientConfigurationBuilder configBuilder =
                ValkeyGlideClientConfiguration.builder();
        
        // Configure TLS if enabled
        if (driverConfig.isTlsEnabled()) {
            configBuilder.useSsl();
        }
        
        ValkeyGlideClientConfiguration clientConfig = configBuilder.build();
        
        ValkeyGlideConnectionFactory glideFactory;
        if (driverConfig.isClusterMode()) {
            ValkeyClusterConfiguration clusterConfig = new ValkeyClusterConfiguration();
            clusterConfig.addClusterNode(new ValkeyNode(host, port));
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    clusterConfig.setUsername(auth.getUsername());
                }
                clusterConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            
            glideFactory = new ValkeyGlideConnectionFactory(clusterConfig, clientConfig);
        } else {
            ValkeyStandaloneConfiguration standaloneConfig = new ValkeyStandaloneConfiguration(host, port);
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    standaloneConfig.setUsername(auth.getUsername());
                }
                standaloneConfig.setPassword(ValkeyPassword.of(auth.getPassword()));
            }
            
            glideFactory = new ValkeyGlideConnectionFactory(standaloneConfig, clientConfig);
        }
        
        // Initialize the factory
        glideFactory.afterPropertiesSet();
        return glideFactory;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public CompletableFuture<TimedResult<Void>> set(byte[] key, byte[] value) {
        return CompletableFuture.supplyAsync(() -> {
            connectionLock.lock();
            try {
                long start = System.nanoTime();
                connection.stringCommands().set(key, value);
                long latencyMicros = (System.nanoTime() - start) / 1000;
                return TimedResult.ofVoid(latencyMicros);
            } finally {
                connectionLock.unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TimedResult<byte[]>> get(byte[] key) {
        return CompletableFuture.supplyAsync(() -> {
            connectionLock.lock();
            try {
                long start = System.nanoTime();
                byte[] result = connection.stringCommands().get(key);
                long latencyMicros = (System.nanoTime() - start) / 1000;
                return TimedResult.of(result, latencyMicros);
            } finally {
                connectionLock.unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping() {
        return CompletableFuture.supplyAsync(() -> {
            connectionLock.lock();
            try {
                long start = System.nanoTime();
                String result = connection.ping();
                long latencyMicros = (System.nanoTime() - start) / 1000;
                return TimedResult.of(result, latencyMicros);
            } finally {
                connectionLock.unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TimedResult<String>> ping(byte[] message) {
        return CompletableFuture.supplyAsync(() -> {
            connectionLock.lock();
            try {
                long start = System.nanoTime();
                byte[] echoResult = connection.echo(message);
                String result = echoResult != null ? new String(echoResult) : "PONG";
                long latencyMicros = (System.nanoTime() - start) / 1000;
                return TimedResult.of(result, latencyMicros);
            } finally {
                connectionLock.unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TimedResult<Long>> del(byte[]... keys) {
        return CompletableFuture.supplyAsync(() -> {
            connectionLock.lock();
            try {
                long start = System.nanoTime();
                Long result = connection.keyCommands().del(keys);
                long latencyMicros = (System.nanoTime() - start) / 1000;
                return TimedResult.of(result, latencyMicros);
            } finally {
                connectionLock.unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TimedResult<Void>> flushDb() {
        return CompletableFuture.supplyAsync(() -> {
            connectionLock.lock();
            try {
                long start = System.nanoTime();
                connection.serverCommands().flushDb();
                long latencyMicros = (System.nanoTime() - start) / 1000;
                return TimedResult.ofVoid(latencyMicros);
            } finally {
                connectionLock.unlock();
            }
        }, executor);
    }

    @Override
    public void close() {
        logger.info("Closing Spring Data Valkey connection ({})", secondaryDriverId);
        connected = false;
        
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                logger.warn("Error closing connection: {}", e.getMessage());
            }
            connection = null;
        }
        
        if (connectionFactory instanceof JedisConnectionFactory jedisFactory) {
            try {
                jedisFactory.destroy();
            } catch (Exception e) {
                logger.warn("Error destroying Jedis connection factory: {}", e.getMessage());
            }
        } else if (connectionFactory instanceof LettuceConnectionFactory lettuceFactory) {
            try {
                lettuceFactory.destroy();
            } catch (Exception e) {
                logger.warn("Error destroying Lettuce connection factory: {}", e.getMessage());
            }
        } else if (connectionFactory instanceof ValkeyGlideConnectionFactory glideFactory) {
            try {
                glideFactory.destroy();
            } catch (Exception e) {
                logger.warn("Error destroying ValkeyGlide connection factory: {}", e.getMessage());
            }
        }
        connectionFactory = null;
        
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }
}