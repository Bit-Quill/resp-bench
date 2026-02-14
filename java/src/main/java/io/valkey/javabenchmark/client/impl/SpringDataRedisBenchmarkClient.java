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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Spring Data Redis implementation of BenchmarkClient.
 * Supports Jedis and Lettuce as underlying drivers via secondary_driver_id configuration.
 * Default: Jedis if secondary_driver_id is not specified.
 *
 * @author Ilia Kolominsky
 */
public class SpringDataRedisBenchmarkClient implements BenchmarkClient {

    private static final Logger logger = LoggerFactory.getLogger(SpringDataRedisBenchmarkClient.class);

    private RedisConnectionFactory connectionFactory;
    private RedisConnection connection;
    private final ReentrantLock connectionLock = new ReentrantLock();
    private boolean connected;
    private ExecutorService executor;
    private String secondaryDriverId;

    @Override
    public String getDriverId() {
        return "spring-data-redis";
    }

    @Override
    public String getDescription() {
        return "Spring Data Redis (requires secondary_driver_id: jedis or lettuce)";
    }

    @Override
    public void connect(String host, int port, DriverConfig driverConfig) throws ClientException {
        // Get secondary driver id (jedis or lettuce)
        this.secondaryDriverId = driverConfig.getSecondaryDriverId();
        if (secondaryDriverId == null || secondaryDriverId.isEmpty()) {
            secondaryDriverId = "jedis"; // Default to jedis
        }
        
        logger.info("Connecting Spring Data Redis to {}:{} (cluster={}, secondary_driver={})", 
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
                default:
                    throw new ClientException("Unsupported secondary_driver_id for spring-data-redis: " + secondaryDriverId + 
                            ". Supported values: jedis, lettuce");
            }
            
            // Get connection
            connection = connectionFactory.getConnection();
            
            // Test connection
            String pingResponse = connection.ping();
            if (!"PONG".equals(pingResponse)) {
                throw new ClientException("Unexpected ping response: " + pingResponse);
            }
            
            connected = true;
            logger.info("Spring Data Redis connected successfully using {}", secondaryDriverId);
            
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("Failed to connect Spring Data Redis to " + host + ":" + port, e);
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
            RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration();
            clusterConfig.addClusterNode(new RedisNode(host, port));
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    clusterConfig.setUsername(auth.getUsername());
                }
                clusterConfig.setPassword(RedisPassword.of(auth.getPassword()));
            }
            
            jedisFactory = new JedisConnectionFactory(clusterConfig, clientConfig);
        } else {
            RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(host, port);
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    standaloneConfig.setUsername(auth.getUsername());
                }
                standaloneConfig.setPassword(RedisPassword.of(auth.getPassword()));
            }
            
            jedisFactory = new JedisConnectionFactory(standaloneConfig, clientConfig);
        }
        
        // Initialize the factory
        jedisFactory.afterPropertiesSet();
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
            RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration();
            clusterConfig.addClusterNode(new RedisNode(host, port));
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    clusterConfig.setUsername(auth.getUsername());
                }
                clusterConfig.setPassword(RedisPassword.of(auth.getPassword()));
            }
            
            lettuceFactory = new LettuceConnectionFactory(clusterConfig, clientConfig);
        } else {
            RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(host, port);
            
            // Configure authentication if provided
            if (driverConfig.hasAuth()) {
                DriverConfig.AuthConfig auth = driverConfig.getAuth();
                if (auth.getUsername() != null && !auth.getUsername().isEmpty()) {
                    standaloneConfig.setUsername(auth.getUsername());
                }
                standaloneConfig.setPassword(RedisPassword.of(auth.getPassword()));
            }
            
            lettuceFactory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        }
        
        // Initialize the factory
        lettuceFactory.afterPropertiesSet();
        return lettuceFactory;
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
        logger.info("Closing Spring Data Redis connection ({})", secondaryDriverId);
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
        }
        connectionFactory = null;
        
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }
}